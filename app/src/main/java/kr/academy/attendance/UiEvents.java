package kr.academy.attendance;

import java.util.ArrayDeque;
import java.util.function.Consumer;

/** UI-thread event queue; document results can arrive before the local page is ready. */
public final class UiEvents {
    private Consumer<String> deliver;
    private final ArrayDeque<String> pending=new ArrayDeque<>();
    private boolean ready,closed;
    public UiEvents(Consumer<String> deliver){this.deliver=deliver;}
    public void detach(){ready=false;deliver=null;}
    public void attach(Consumer<String> next){if(!closed){deliver=next;ready=false;}}
    public void post(String script){
        if(closed)return;
        if(ready)deliver.accept(script);else pending.addLast(script);
    }
    public void ready(){
        if(closed||ready||deliver==null)return;
        ready=true;
        while(!pending.isEmpty())deliver.accept(pending.removeFirst());
    }
    public void close(){closed=true;pending.clear();detach();}
}
