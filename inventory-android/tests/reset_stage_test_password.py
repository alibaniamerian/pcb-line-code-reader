"""Interactive password reset for the one local synthetic stage-test user only."""
import getpass
import sqlite3
from pathlib import Path
from passlib.context import CryptContext

root=Path(__file__).resolve().parents[1]/'.sandbox/stages-001'
assert (root/'EIS_ISOLATED_SANDBOX').read_text()=='production-controls-v1'
db=sqlite3.connect((root/'sandbox.sqlite').as_uri()+'?mode=rw',uri=True)
try:
    assert db.execute('SELECT order_number FROM production_orders').fetchall()==[('TEST-MOBILE-001',)]
    assert db.execute('SELECT username FROM users').fetchall()==[('mobile-test',)]
    print('LOCAL stages-001 ONLY — set a test password for mobile-test. No production account is affected.')
    while True:
        password=getpass.getpass('New test password (12+ characters): ')
        confirmation=getpass.getpass('Repeat new test password: ')
        if len(password)<12 or len(password.encode('utf-8'))>72:
            print('Use at least 12 characters and at most 72 UTF-8 bytes.')
            continue
        if password!=confirmation:
            print('Passwords did not match. Try again.')
            continue
        break
    encoded=CryptContext(schemes=['bcrypt'],deprecated='auto').hash(password)
    password=confirmation=None
    db.execute('BEGIN IMMEDIATE')
    assert db.execute('SELECT order_number FROM production_orders').fetchall()==[('TEST-MOBILE-001',)]
    assert db.execute('SELECT username FROM users').fetchall()==[('mobile-test',)]
    changed=db.execute('UPDATE users SET hashed_password=? WHERE username=?',(encoded,'mobile-test'))
    assert changed.rowcount==1
    db.commit()
    encoded=None
    print('Test password updated. Sign in on OZAK Stages TEST with mobile-test. No test records were deleted.')
finally:
    db.close()
