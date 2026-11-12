"""Build the normal operator UI against a loopback-only isolated server."""
from pathlib import Path
import os
import shutil
# Loopback port of the isolated sandbox server (adb reverse tcp:PORT tcp:PORT).
PORT=int(os.environ.get('SANDBOX_PORT','18083'))
assert 1024<PORT<65536
root = Path(__file__).resolve().parents[1]
source = root / 'app/src/main'
target = root / 'app/build/operatorSandbox'
target.mkdir(parents=True, exist_ok=True)
shutil.copytree(source/'java', target/'java', dirs_exist_ok=True)
java = target/'java/com/ozak/inventory'
def change(name, old, new):
    path = java/name
    text = path.read_text(encoding='utf-8')
    assert old in text, name
    path.write_text(text.replace(old, new), encoding='utf-8')
change('BridgePolicy.java', 'https://inventory.askpart.com', f'http://127.0.0.1:{PORT}')
change('BridgePolicy.java', '"https".equals(u.getScheme())&&"inventory.askpart.com".equals(u.getHost())', '"http".equals(u.getScheme())&&"127.0.0.1".equals(u.getHost())')
change('BridgePolicy.java', '(u.getPort()==-1||u.getPort()==443)', f'(u.getPort()=={PORT})')
change('OperatorActivity.java', 'return !"https".equals(u.getScheme())||u.getUserInfo()!=null;', f'return !"http".equals(u.getScheme())||!"127.0.0.1".equals(u.getHost())||u.getPort()!={PORT}||u.getUserInfo()!=null;')
change('OperatorBridge.java', 'if(!role)throw new JSONException("permission");', 'if(!role||!("mobile-test".equals(user.getString("username"))||"mobile-test-2".equals(user.getString("username"))))throw new JSONException("permission");')
change('OperatorActivity.java', 'private void select(JSONObject selected){', 'private void select(JSONObject selected){if(!"TEST-MOBILE-001".equals(selected.optString("order_number"))){say("Only the synthetic test order is allowed.");return;}')
# Only the normal operator activity is launchable; no production login/demo entry.
manifest = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
<uses-permission android:name="android.permission.INTERNET" />
<application android:label="OZAK Operator TEST" android:icon="@mipmap/ic_launcher" android:theme="@style/AppTheme" android:allowBackup="false" android:supportsRtl="true" android:networkSecurityConfig="@xml/sandbox_network">
<activity android:name="com.ozak.inventory.OperatorActivity" android:exported="true" android:windowSoftInputMode="adjustResize">
<intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter>
</activity></application></manifest>'''
(target/'AndroidManifest.xml').write_text(manifest, encoding='utf-8')
print(f'Prepared normal operator UI: localhost:{PORT}, isolated app storage, synthetic user/order only.')
