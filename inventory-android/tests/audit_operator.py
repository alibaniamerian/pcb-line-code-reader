"""Read-only audit restricted to the fresh single-order stage sandbox."""
import json, sqlite3, sys
from pathlib import Path
root=Path(__file__).resolve().parents[1]/'.sandbox/operator-001'
assert (root/'EIS_ISOLATED_SANDBOX').read_text()=='production-controls-v1'
db=sqlite3.connect((root/'sandbox.sqlite').as_uri()+'?mode=ro',uri=True)
db.execute('PRAGMA query_only=ON')
assert db.execute('select order_number from production_orders').fetchall()==[('TEST-MOBILE-001',)]
tables=['production_pcb_entries','production_board_stage_events','production_package_receipts','production_control_requests','production_material_events','production_supplemental_reels']
counts={t:db.execute('select count(*) from '+t).fetchone()[0] for t in tables}
result={'counts':counts,'packages':db.execute('select package_code,current_quantity from legacy_packages order by id').fetchall(),'inventory':db.execute('select item_id,warehouse_id,location_id,quantity from inventory_transactions order by id').fetchall(),'allocations':db.execute('select package_code,planned_quantity,consumed_quantity,status from production_material_allocations order by id').fetchall()}
result=json.loads(json.dumps(result))
if '--final' in sys.argv:
    baseline=json.loads((root/'audit-baseline.json').read_text())
    for k in ['packages','inventory','allocations']: assert result[k]==baseline[k],k+' changed'
    assert counts['production_pcb_entries']==1,counts
    assert counts['production_board_stage_events']==8,counts
    assert counts['production_package_receipts']==1,counts
    assert counts['production_material_events']==0 and counts['production_supplemental_reels']==0,counts
    assert db.execute('select count(distinct user_id) from production_board_stage_events').fetchone()[0]==1
    assert db.execute("select count(*) from production_board_stage_events where outcome='FAIL'").fetchone()[0]==1
    assert db.execute("select count(*) from production_board_stage_events where outcome='PASS'").fetchone()[0]>=1
    assert db.execute('select count(distinct unit_id) from production_board_stage_events').fetchone()[0]==1
    assert all(row[2]==0 for row in result['allocations'])
    result['verdict']='PASS: single operator; one PCB and one receipt; FAIL preserved; inventory unchanged'
    (root/'audit-final.json').write_text(json.dumps(result,indent=2))
else:
    assert all(n==0 for n in counts.values())
    target=root/'audit-baseline.json'
    if not target.exists():target.write_text(json.dumps(result,indent=2))
print(json.dumps(result,indent=2))
db.close()

