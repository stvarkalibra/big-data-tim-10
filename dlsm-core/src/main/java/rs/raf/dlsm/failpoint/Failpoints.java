package rs.raf.dlsm.failpoint;

import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.io.InjectedIOException;

import java.util.OptionalInt;

public interface Failpoints {
    void hit(String name, OpContext ctx) throws InjectedIOException;
    OptionalInt intParameter(String name, OpContext ctx, String param);
}
