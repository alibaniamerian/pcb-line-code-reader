param([int]$X=-1,[int]$Y=-1)
$ErrorActionPreference='Stop'
$adb='C:/Users/HP/AppData/Local/Android/Sdk/platform-tools/adb.exe'
if($X -ge 0 -and $Y -ge 0){& $adb shell input tap $X $Y}
& $adb shell uiautomator dump /data/local/tmp/ozak-main-ui.xml | Out-Null
try{
 [xml]$ui=((& $adb shell cat /data/local/tmp/ozak-main-ui.xml)-join '')
 $ui.SelectNodes('//node[@package="com.ozak.inventory" and not(ancestor::node[@class="android.webkit.WebView"]) and (@class="android.widget.Button" or @class="android.widget.TextView")]') | ForEach-Object {$_.text+' | '+$_.bounds+' | '+$_.enabled}
 $ui.SelectNodes('//node[@text="Approve app connection"]') | ForEach-Object {$_.text+' | '+$_.bounds+' | '+$_.enabled}
}finally{& $adb shell rm -f /data/local/tmp/ozak-main-ui.xml}


