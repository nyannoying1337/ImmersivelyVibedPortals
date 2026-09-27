package qouteall.q_misc_util.my_util;

/**
 * Replacement for net.minecraft.util.Tuple, which was removed in 26.x.
 */
public class Tuple<A, B> {
    private A a;
    private B b;
    
    public Tuple(A a, B b) {
        this.a = a;
        this.b = b;
    }
    
    public A getA() {
        return a;
    }
    
    public void setA(A a) {
        this.a = a;
    }
    
    public B getB() {
        return b;
    }
    
    public void setB(B b) {
        this.b = b;
    }
}
