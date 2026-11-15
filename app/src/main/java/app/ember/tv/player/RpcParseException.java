package app.ember.tv.player;

import java.io.IOException;

/** Android equivalent for the RPC library's desktop-only unmarshalling exception. */
public final class RpcParseException extends IOException {
    public RpcParseException(String message) { super(message); }
    public RpcParseException(String message, Exception cause) { super(message, cause); }
}
