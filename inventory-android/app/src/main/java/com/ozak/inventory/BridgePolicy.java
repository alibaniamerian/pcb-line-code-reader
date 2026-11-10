package com.ozak.inventory;

import java.net.URI;
import java.util.regex.Pattern;

/** Release policy: exact production origin and read-only connection checks. */
final class BridgePolicy {
    static final String ORIGIN="https://inventory.askpart.com";
    static final String PAGE=ORIGIN+"/mobile/production-connect";
    private static final Pattern UUID_PATTERN=Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    static boolean uuid(String value){return value!=null&&UUID_PATTERN.matcher(value).matches();}
    static boolean origin(String value){
        try {
            URI u=new URI(value);
            return "https".equals(u.getScheme())&&"inventory.askpart.com".equals(u.getHost())
                &&(u.getPort()==-1||u.getPort()==443)&&u.getUserInfo()==null
                &&u.getRawQuery()==null&&u.getRawFragment()==null
                &&(u.getRawPath()==null||u.getRawPath().isEmpty()||u.getRawPath().equals("/"));
        }catch(Exception e){return false;}
    }
    static boolean page(String value){
        try {
            URI u=new URI(value);
            return "https".equals(u.getScheme())&&"inventory.askpart.com".equals(u.getHost())
                &&(u.getPort()==-1||u.getPort()==443)&&u.getUserInfo()==null
                &&"/mobile/production-connect".equals(u.getRawPath())
                &&u.getRawQuery()==null&&u.getRawFragment()==null;
        }catch(Exception e){return false;}
    }
    static boolean accepts(boolean mainFrame,String source,String current){return mainFrame&&origin(source)&&page(current);}
    static boolean operation(String op){return "SESSION".equals(op)||"ORDERS".equals(op);}
}
