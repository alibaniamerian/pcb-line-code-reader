package com.ozak.inventory;

public final class DemoStoreTest {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        DemoStore s=new DemoStore();
        s.send("receive","DEMO-R02",DemoStore.Outcome.ACCEPT);
        check(s.reel("DEMO-R02").received,"Scanned package must be received");
        check(!s.reel("DEMO-R03").received,"Receipt must not receive other packages");
        int count=s.events;
        s.send("receive","DEMO-R02",DemoStore.Outcome.ACCEPT);
        check(s.events==count,"Repeated receipt must not create another event");
        s.send("receive","DEMO-S01",DemoStore.Outcome.ACCEPT);
        check(!s.reel("DEMO-S01").received,"Same item does not authorize another package");
        check(s.deliveredItems("res-10k").size()==1,"Part search");
        check(s.deliveredItems("DEMO-OZ01").size()==1,"OZCODE search");
        check(s.deliveredItems("resistor").size()==1,"Description search");
        check(s.deliveredItems("missing").isEmpty(),"Empty search result");
        DemoStore.Reel candidate=s.suggest("DEMO-OZ01");
        check(candidate!=null&&!candidate.reserved,"Suggestion alone does not reserve");
        int quantity=candidate.quantity;
        s.send("reserve",candidate.barcode,DemoStore.Outcome.ACCEPT);
        check(candidate.reserved&&!candidate.received,"Whole reservation is separate from receipt");
        s.send("receive",candidate.barcode,DemoStore.Outcome.ACCEPT);
        check(candidate.received&&candidate.quantity==quantity,"Receipt does not consume stock");
        check(s.reel("DEMO-R01").quantity==5000,"Previous reel is not emptied");
        check(s.deliveredItems("resistor").size()==1,"Multiple reels must remain one item row");
        s.send("receive","DEMO-R03",DemoStore.Outcome.REJECT);
        check(!s.reel("DEMO-R03").received,"Rejected response must not receive");
        String reply=s.send("receive","DEMO-R03",DemoStore.Outcome.TIMEOUT);
        check(reply.contains("unknown")&&!reply.contains("accepted"),"Timeout is not acceptance");
        String id=s.pending.id;count=s.events;
        s.send("pcb","AAA00001",DemoStore.Outcome.ACCEPT);
        check(s.pending.id.equals(id)&&s.boards.isEmpty(),"Unknown request cannot be overwritten");
        s.retry(DemoStore.Outcome.TIMEOUT);
        check(s.pending.id.equals(id)&&s.events==count,"Repeated timeout reuses idempotency key");
        s.retry(DemoStore.Outcome.ACCEPT);
        check(s.pending==null&&s.events==count,"Lost committed response retry must not create event");
        s.send("pcb","AAA00002",DemoStore.Outcome.ACCEPT);
        s.send("pcb","AAA00001",DemoStore.Outcome.ACCEPT);
        s.send("pcb","AAA00002",DemoStore.Outcome.ACCEPT);
        check(s.boards.size()==2,"Unordered serials, one row per PCB");
        s.send("pcb","AAA00003",DemoStore.Outcome.ACCEPT);
        check(s.boards.size()==2,"Order capacity enforced by mock server");
        for(String action:new String[]{"pcb","receive","reserve"}){
            String value=action.equals("pcb")?"AAA00001":action.equals("receive")?"DEMO-R02":"DEMO-S01";
            DemoStore before=new DemoStore();
            reply=before.send(action,value,DemoStore.Outcome.TIMEOUT_BEFORE_COMMIT);
            check(reply.contains("unknown")&&before.events==0,"Pre-commit timeout must stay unknown without mutation");
            id=before.pending.id;
            before.retry(DemoStore.Outcome.TIMEOUT_BEFORE_COMMIT);
            check(before.pending.id.equals(id)&&before.events==0,"Pre-commit retry retains request identity");
            before.retry(DemoStore.Outcome.ACCEPT);
            check(before.pending==null&&before.events==1,"Retry after pre-commit timeout commits once");
            DemoStore after=new DemoStore();
            after.send(action,value,DemoStore.Outcome.TIMEOUT);
            after.retry(DemoStore.Outcome.TIMEOUT_BEFORE_COMMIT);
            check(after.events==1&&after.pending!=null,"Later network failure cannot undo original commit");
            after.retry(DemoStore.Outcome.ACCEPT);
            check(after.events==1&&after.pending==null,"Post-commit retry returns cached result");
            DemoStore rejected=new DemoStore();
            rejected.send(action,value,DemoStore.Outcome.TIMEOUT_BEFORE_COMMIT);
            rejected.retry(DemoStore.Outcome.REJECT);
            check(rejected.events==0&&rejected.pending==null,"A definitive rejection resolves an uncommitted unknown request");
        }
        System.out.println("PASS: individual receipt, exact package, supplemental reservation, no consumption, search, PCB progress and pre/post-commit retries for all three operations.");
    }
}
