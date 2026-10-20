package rs.raf.dlsm.failpoint;

import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.io.InjectedIOException;

import java.util.OptionalInt;

public class NoOpFailpoints implements Failpoints {
    @Override
    public void hit(String name, OpContext ctx) throws InjectedIOException {
        // Za sada ne radi ništa, kasnije ovde ubacujemo proveru aktivnih pravila (PAUSE, CRASH, IO_ERROR)
    }

    @Override
    public OptionalInt intParameter(String name, OpContext ctx, String param) {
        return OptionalInt.empty();
    }
}
