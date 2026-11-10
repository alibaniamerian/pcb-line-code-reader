"""Read-only audit of the delivered isolated fixture. Never reads users, keys or payloads."""
import argparse
import json
from pathlib import Path
import sqlite3

parser = argparse.ArgumentParser()
parser.add_argument('--final', action='store_true')
parser.add_argument('--test', choices=['test-001','test-002'], default='test-001')
parser.add_argument('--advanced', action='store_true')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1] / '.sandbox' / args.test
assert (root / 'EIS_ISOLATED_SANDBOX').read_text() == 'production-controls-v1'
db = sqlite3.connect((root / 'sandbox.sqlite').as_uri() + '?mode=ro', uri=True)
db.execute('PRAGMA query_only=ON')
orders = db.execute('SELECT order_number FROM production_orders').fetchall()
assert orders == [('TEST-MOBILE-001',)], 'Refusing to audit a non-synthetic order set'
packages = db.execute('SELECT package_code, current_quantity FROM legacy_packages ORDER BY package_code').fetchall()
inventory = db.execute('SELECT item_id, warehouse_id, location_id, quantity FROM inventory_transactions ORDER BY id').fetchall()
counts = {name: db.execute('SELECT count(*) FROM ' + name).fetchone()[0] for name in (
    'production_pcb_entries', 'production_package_receipts', 'production_supplemental_reels',
    'production_control_requests', 'production_material_events')}
allocations = db.execute('SELECT package_code, planned_quantity, consumed_quantity, status FROM production_material_allocations ORDER BY id').fetchall()
result = dict(order='TEST-MOBILE-001', packages=packages, inventory=inventory, counts=counts, allocations=allocations)
if args.final:
    baseline = json.loads((root / 'audit-baseline.json').read_text())
    assert json.loads(json.dumps(packages)) == baseline['packages'], 'Physical package quantities changed'
    assert json.loads(json.dumps(inventory)) == baseline['inventory'], 'Inventory ledger changed'
    assert counts['production_pcb_entries'] == 2
    assert counts['production_package_receipts'] == 3
    assert counts['production_supplemental_reels'] == 1
    assert counts['production_control_requests'] == (12 if args.advanced else 10), 'Retries created unexpected request records'
    assert counts['production_material_events'] == 1, 'Unexpected material event'
    assert all(row[2] == 0 for row in allocations), 'Consumption changed'
    assert len(allocations) == 3 and allocations[-1][1] == 0, 'Supplement must reserve with zero planned usage'
    result['verdict'] = 'PASS: stock unchanged; exact event/request counts; no duplicate retries'
    (root / 'audit-final.json').write_text(json.dumps(result, indent=2))
else:
    assert all(value == 0 for value in counts.values()), 'Baseline must precede tests'
    target = root / 'audit-baseline.json'
    if not target.exists():
        target.write_text(json.dumps(result, indent=2))
print(json.dumps(result, indent=2))
db.close()
