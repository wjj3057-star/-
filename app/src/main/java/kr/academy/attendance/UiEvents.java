package kr.academy.attendance;

import java.util.ArrayDeque;
import java.util.function.Consumer;

/** UI-thread event queue; document results can arrive before the local page is ready. */
public final class UiEvents {
    private final Consumer<String> deliver;
    private final ArrayDeque<String> pending=new ArrayDeque<>();
    private boolean ready,closed;
    public UiEvents(Consumer<String> deliver){this.deliver=deliver;}
    public void post(String script){
        if(closed)return;
        if(ready)deliver.accept(script);else pending.addLast(script);
    }
    public void ready(){
        if(closed||ready)return;
        ready=true;
        while(!pending.isEmpty())deliver.accept(pending.removeFirst());
    }
    public void close(){closed=true;pending.clear();}
}
