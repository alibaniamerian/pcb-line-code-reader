package com.ozak.inventory;

import java.util.*;

/** In-memory fake server. Fixture identifiers are not a production QR contract. No I/O. */
final class DemoStore {
    enum Outcome { ACCEPT, REJECT, TIMEOUT, TIMEOUT_BEFORE_COMMIT }
    static final class Reel {
        final String barcode, part, ozcode, description, warehouse, location;
        final int quantity;
        boolean reserved, received;
        Reel(String barcode, String part, String ozcode, String description, int quantity,
             String warehouse, String location, boolean reserved, boolean received) {
            this.barcode=barcode; this.part=part; this.ozcode=ozcode; this.description=description;
            this.quantity=quantity; this.warehouse=warehouse; this.location=location;
            this.reserved=reserved; this.received=received;
        }
        String details() { return barcode+" | "+quantity+" units\n"+warehouse+" / "+location; }
    }
    static final class Request {
        final String id=UUID.randomUUID().toString(), action, value;
        Request(String action,String value) { this.action=action; this.value=value; }
    }
    final List<Reel> reels=new ArrayList<>();
    final Set<String> boards=new LinkedHashSet<>();
    final Map<String,String> replies=new HashMap<>();
    Request pending;
    int events;
    DemoStore() {
        reels.add(new Reel("DEMO-R01","RES-10K","DEMO-OZ01","10 kOhm resistor",5000,"Line store","Machine A",true,true));
        reels.add(new Reel("DEMO-R02","CAP-100N","DEMO-OZ02","100 nF capacitor",4000,"Main store","A-02",true,false));
        reels.add(new Reel("DEMO-R03","RES-10K","DEMO-OZ01","10 kOhm resistor",3000,"Main store","B-03",true,false));
        reels.add(new Reel("DEMO-S01","RES-10K","DEMO-OZ01","10 kOhm resistor",5000,"Remote store","C-04",false,false));
        reels.add(new Reel("DEMO-S02","CAP-100N","DEMO-OZ02","100 nF capacitor",4000,"Main store","A-05",false,false));
    }
    Reel reel(String code) { for(Reel r:reels) if(r.barcode.equals(code))return r; return null; }
    List<Reel> deliveredItems(String query) {
        List<Reel> result=new ArrayList<>(); Set<String> seen=new HashSet<>();
        String q=query.toLowerCase(Locale.ROOT);
        for(Reel r:reels) if(r.received && seen.add(r.ozcode)
            && (r.part+" "+r.ozcode+" "+r.description).toLowerCase(Locale.ROOT).contains(q))result.add(r);
        return result;
    }
    Reel suggest(String item) { for(Reel r:reels)if(r.ozcode.equals(item)&&!r.reserved)return r;return null; }
    String send(String action,String value,Outcome outcome) {
        if(pending!=null)return "MOCK: Resolve the unknown request before starting another action.";
        return respond(new Request(action,value),outcome);
    }
    String retry(Outcome outcome) {
        return pending==null ? "MOCK: No pending request." : respond(pending,outcome);
    }
    private String respond(Request request,Outcome outcome) {
        // Simulate a lost response AFTER fake-server commit. Client must not show acceptance.
        if(outcome==Outcome.TIMEOUT || outcome==Outcome.TIMEOUT_BEFORE_COMMIT) {
            if(outcome==Outcome.TIMEOUT && !replies.containsKey(request.id))replies.put(request.id,apply(request));
            pending=request;
            return "MOCK: Timeout — outcome unknown. Retry the SAME request to retrieve its response.";
        }
        String reply=replies.get(request.id);
        if(reply==null) {
            reply=outcome==Outcome.REJECT ? "MOCK: Rejected by simulated server." : apply(request);
            replies.put(request.id,reply);
        }
        pending=null;
        return reply;
    }
    private String apply(Request request) {
        if(request.action.equals("pcb")) {
            if(!Arrays.asList("AAA00001","AAA00002","AAA00003").contains(request.value))
                return "MOCK: Serial is not mapped to this order's PCB item.";
            if(boards.contains(request.value))return "MOCK: Already entered — count unchanged.";
            if(boards.size()>=2)return "MOCK: Order capacity reached.";
            boards.add(request.value);events++;
            return "MOCK: PCB entry accepted by simulated server.";
        }
        Reel reel=reel(request.value);
        if(reel==null)return "MOCK: Unknown package.";
        if(request.action.equals("reserve")) {
            if(reel.reserved)return "MOCK: Package already reserved — no new reservation.";
            boolean delivered=false;
            for(Reel r:reels)if(r.received&&r.ozcode.equals(reel.ozcode))delivered=true;
            if(!delivered)return "MOCK: Item has not been delivered to this line.";
            reel.reserved=true;events++;
            return "MOCK: Whole package reserved. Not received. Any transfer uses the existing process.";
        }
        if(!request.action.equals("receive"))return "MOCK: Unsupported operation.";
        if(!reel.reserved)return "MOCK: This exact package is not reserved for the order.";
        if(reel.received)return "MOCK: Already received — count unchanged.";
        reel.received=true;events++;
        return "MOCK: Only "+reel.barcode+" received. No consumption recorded.";
    }
}
