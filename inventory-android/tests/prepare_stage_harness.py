from pathlib import Path
root=Path(__file__).resolve().parents[1]
s=(root/'app/src/sandbox/java/com/ozak/inventory/SandboxActivity.java').read_text(encoding='utf-8')
s=s.replace('SandboxActivity','StageTestActivity').replace('SandboxScenario','StageScenario')
s=s.replace('"ORDERS","ROUTE"','"ORDERS","ROUTE","BOARD_CONTEXT","STAGE_PASSAGE"')
s=s.replace('&&!data.getBoolean("later_stages_enabled")','')
s=s.replace('sandbox-journal.json','stages-journal.json').replace('suite.step()==22','suite.step()==32')
s=s.replace('(SandboxBuild.ADVANCED?32:26)','36')
s=s.replace('if(!suite.done()&&suite.step()!=14&&suite.step()!=18&&suite.step()!=26&&suite.step()!=28)','if(!suite.done()&&suite.step()!=24&&suite.step()!=32)')
s=s.replace('responseDropped', 'responseDroppedFinal')
s=s.replace('suite.state.put("responseDroppedFinal",true);','suite.state.put("responseDroppedFinal",true);suite.state.put("recoveryVerified",false);')
a=s.index('    private void advance(){');b=s.index('    private void refreshScans()',a)
s=s[:a]+'''    private void advance(){
        if(!connected||correlation!=null||failed||needsRecovery||suite.done())return;
        if(suite.step()==24||suite.step()==32){new AlertDialog.Builder(this).setTitle("Confirm synthetic observation skip")
            .setMessage("TEST-MOBILE-001 / ZZZ00002 only. The server rejected two missing stages. Submit a NEW request with explicit synthetic skip reason? Earlier stages remain NOT_RECORDED.")
            .setNegativeButton("Cancel",null).setPositiveButton("Confirm synthetic skip",(d,w)->{try{suite.state.put(suite.step()==32?"skipApproved32":"skipApproved",true);save();issue();}catch(Exception e){failed=true;disconnect("Could not save skip confirmation.");}}).show();return;}
        issue();
    }
'''+s[b:]
s=s.replace('"FAIL: invalid response or assertion. Test stopped; journal retained."','"FAIL stage "+(suite.step()+1)+": "+(e instanceof IllegalStateException?e.getMessage():"invalid response")+"; journal retained."')
target=root/'app/src/stageTest/java/com/ozak/inventory/StageTestActivity.java'
target.write_text('// Generated isolated stage harness. Never part of production.\n'+s,encoding='utf-8')
print('Prepared single-user isolated stage harness.')
