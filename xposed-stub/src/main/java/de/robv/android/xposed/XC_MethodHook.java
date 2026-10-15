package de.robv.android.xposed;
import de.robv.android.xposed.callbacks.XCallback;
import java.lang.reflect.Member;
public abstract class XC_MethodHook extends XCallback {
    public XC_MethodHook() {}
    public XC_MethodHook(int priority) {}
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
    public static class MethodHookParam extends XCallback.Param {
        public Member method;
        public Object thisObject;
        public Object[] args;
        public Object getResult() { return null; }
        public void setResult(Object result) {}
        public Throwable getThrowable() { return null; }
        public boolean hasThrowable() { return false; }
    }
    public class Unhook {
        public Member getHookedMethod() { return null; }
        public void unhook() {}
    }
}
