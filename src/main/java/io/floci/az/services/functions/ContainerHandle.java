package io.floci.az.services.functions;

/**
 * Represents a running Azure Functions container ready to accept invocations.
 */
public class ContainerHandle {

    public enum State { WARM, BUSY, STOPPED }

    private final String containerId;
    private final String functionKey;   // "{account}/{appName}/{funcName}"
    private final String host;
    private final int port;             // host-mapped port for container's port 80
    private volatile long lastUsedMs;
    private volatile State state;

    public ContainerHandle(String containerId, String functionKey, String host, int port) {
        this.containerId  = containerId;
        this.functionKey  = functionKey;
        this.host         = host;
        this.port         = port;
        this.lastUsedMs   = System.currentTimeMillis();
        this.state        = State.WARM;
    }

    public String containerId()  { return containerId; }
    public String functionKey()  { return functionKey; }
    public String host()         { return host; }
    public int    port()         { return port; }
    public State  state()        { return state; }
    public long   lastUsedMs()   { return lastUsedMs; }

    public void setState(State s)  { this.state = s; }
    public void touchLastUsed()    { this.lastUsedMs = System.currentTimeMillis(); }
}
