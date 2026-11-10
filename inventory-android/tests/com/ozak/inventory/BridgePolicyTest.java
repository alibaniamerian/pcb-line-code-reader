package com.ozak.inventory;

public final class BridgePolicyTest {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        check(BridgePolicy.accepts(true,BridgePolicy.ORIGIN,BridgePolicy.PAGE),"Valid main-frame connection");
        check(!BridgePolicy.accepts(false,BridgePolicy.ORIGIN,BridgePolicy.PAGE),"Iframe must not connect");
        for(String origin:new String[]{"http://inventory.askpart.com","https://evil.test","https://inventory.askpart.com.evil.test","https://inventory.askpart.com:444","https://user@inventory.askpart.com","https://inventory.askpart.com?x=1","null"})
            check(!BridgePolicy.accepts(true,origin,BridgePolicy.PAGE),"Reject origin: "+origin);
        for(String page:new String[]{BridgePolicy.ORIGIN+"/login",BridgePolicy.PAGE+"/",BridgePolicy.PAGE+"?redirect=1",BridgePolicy.PAGE+"#x",BridgePolicy.ORIGIN+"/mobile/%70roduction-connect","http://127.0.0.1:18080/mobile/production-connect"})
            check(!BridgePolicy.accepts(true,BridgePolicy.ORIGIN,page),"Reject page: "+page);
        check(BridgePolicy.operation("SESSION")&&BridgePolicy.operation("ORDERS"),"Allow read-only probes");
        for(String operation:new String[]{"PCB_ENTRY","PACKAGE_RECEIPT","SUPPLEMENT_RESERVE","SUPPLEMENT_DELIVER","USAGE_PLAN","REQUEST_STATUS","https://evil.test"})
            check(!BridgePolicy.operation(operation),"Block non-probe operation: "+operation);
        check(BridgePolicy.uuid("11111111-1111-4111-8111-111111111111"),"UUID accepted");
        check(!BridgePolicy.uuid("1-1-1-1-1")&&!BridgePolicy.uuid(null),"Malformed identity rejected");
        System.out.println("PASS: exact origin/path, main frame, UUID and read-only operation policy.");
    }
}
