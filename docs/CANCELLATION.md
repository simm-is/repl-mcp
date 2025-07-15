# MCP Cancellation and nREPL Interrupt Integration

This document describes the implementation of cancellation support for the MCP (Model Context Protocol) server, focusing on the integration with nREPL's interrupt mechanism.

## Overview

The MCP specification supports optional cancellation of in-progress requests through `notifications/cancelled` notifications. This document outlines how to implement comprehensive cancellation support that works with nREPL's sophisticated interrupt mechanism and other tool execution patterns.

## MCP Cancellation Specification

### Notification Format

When a client wants to cancel an in-progress request, it sends a `notifications/cancelled` notification:

```json
{
  "jsonrpc": "2.0",
  "method": "notifications/cancelled",
  "params": {
    "requestId": "123",
    "reason": "User requested cancellation"
  }
}
```

### Key Requirements

1. **Request Reference**: Cancellation notifications MUST only reference valid in-progress requests
2. **Race Conditions**: Both parties MUST handle race conditions gracefully
3. **Fire-and-Forget**: Notifications maintain fire-and-forget semantics
4. **Error Handling**: Invalid cancellation notifications SHOULD be ignored

## nREPL Interrupt Mechanism

### Core Architecture

nREPL implements a sophisticated three-stage interrupt mechanism:

1. **Stage 1**: Send interrupt signal via `Thread.interrupt()`
2. **Stage 2**: Wait 100ms for graceful response
3. **Stage 3**: Asynchronously wait 5000ms, then force stop if needed

### Implementation Details

#### Message Protocol
```clojure
{:op "interrupt"
 :session "session-id"
 :interrupt-id "message-id-to-interrupt"  ; optional
 :id "interrupt-request-id"}
```

#### Response Statuses
- `:interrupted` - Request found and interruption attempted
- `:session-idle` - Session not executing anything
- `:interrupt-id-mismatch` - Wrong message ID provided
- `:session-ephemeral` - Cannot interrupt ephemeral sessions

#### Thread Management
- Each session runs on a dedicated `SessionThread`
- When interrupted, the entire thread is replaced
- Dynamic bindings are preserved across interruptions
- ThreadLocals are **not** preserved

#### JVM Compatibility
- **JDK ≤19**: Uses `Thread.stop()` (deprecated but functional)
- **JDK 20+**: Uses JVMTI native agent with `-Djdk.attach.allowAttachSelf`

## Tool Categorization for Cancellation

### Category 1: nREPL-Dependent Tools (32 tools)

**Tools**: Performance profiling, code evaluation, testing, navigation, refactoring

**Cancellation Method**: nREPL interrupt message + thread interrupt

**Long-running operations**:
- Performance tools (`profile-cpu`, `profile-alloc`): 1-10 second profiling
- Eval tools (`eval`, `load-file`): Potentially infinite loops
- Test tools (`test-all`, `test-var-query`): Large test suites
- Navigation tools (`call-hierarchy`, `usage-finder`): Project-wide analysis

**Quick operations**:
- Cider-nREPL tools (format, complete, eldoc, etc.): Usually <100ms
- Refactor-nREPL tools (clean-ns, find-symbol, etc.): Usually <1s

### Category 2: Filesystem-Heavy Tools (6 tools)

**Tools**: `lint-project`, `find-function-usages-in-project`, `rename-function-across-project`

**Cancellation Method**: Thread interrupt

**Implementation**: Direct thread interruption on file I/O operations

### Category 3: Pure/Structural Tools (13 tools)

**Tools**: Session management, node replacement, insertion, dependency management

**Cancellation Method**: Cooperative cancellation with tokens

**Implementation**: Check cancellation status between operations

## Implementation Strategy

### 1. Cancellation Registry

```clojure
(defonce cancellation-registry (atom {}))

(defn register-operation! [request-id operation-info]
  (swap! cancellation-registry assoc request-id operation-info))

(defn unregister-operation! [request-id]
  (swap! cancellation-registry dissoc request-id))

(defn get-active-operation [request-id]
  (get @cancellation-registry request-id))
```

### 2. MCP Notification Handler

```clojure
;; Register in Java MCP server
(defn create-cancellation-notification-handler []
  (reify NotificationHandler
    (handle [_ exchange params]
      (let [request-id (get params "requestId")
            reason (get params "reason")]
        (log/log! {:level :info :msg "Cancellation requested" 
                   :data {:request-id request-id :reason reason}})
        (cancel-operation! request-id)
        (Mono/empty)))))

;; Add to notification handlers map
(def notification-handlers
  {"notifications/cancelled" (create-cancellation-notification-handler)
   ;; ... other handlers
   })
```

### 3. nREPL Integration

#### Enhanced nREPL Client
```clojure
(defrecord InterruptibleNreplClient [client session-id operation-registry]
  (eval-with-interrupt [this code]
    (let [msg-id (uuid)]
      (register-operation! operation-registry msg-id this)
      (nrepl/message client {:op "eval" :code code :session session-id :id msg-id})))
  
  (interrupt-operation [this msg-id]
    (nrepl/message client {:op "interrupt" :session session-id :interrupt-id msg-id})))
```

#### Message ID Tracking
```clojure
(defn execute-nrepl-with-cancellation [nrepl-client code request-id]
  (let [msg-id (str (gensym "msg-"))
        eval-msg {:op "eval" :code code :id msg-id}]
    
    ;; Register for cancellation
    (register-operation! request-id 
                         {:type :nrepl
                          :nrepl-client nrepl-client
                          :session-id (:session eval-msg)
                          :msg-id msg-id
                          :thread (Thread/currentThread)})
    
    ;; Execute with cleanup
    (try
      (nrepl/message nrepl-client eval-msg)
      (finally
        (unregister-operation! request-id)))))
```

### 4. Cancellation Dispatch

```clojure
(defn cancel-operation! [request-id]
  (when-let [op-info (get-active-operation request-id)]
    (case (:type op-info)
      :nrepl (cancel-nrepl-operation! op-info)
      :filesystem (cancel-filesystem-operation! op-info)
      :cooperative (cancel-cooperative-operation! op-info)
      (log/log! {:level :warn :msg "Unknown operation type" 
                 :data {:request-id request-id :type (:type op-info)}}))))

(defn cancel-nrepl-operation! [op-info]
  (when-let [msg-id (:msg-id op-info)]
    (nrepl/message (:nrepl-client op-info) 
                   {:op "interrupt" 
                    :session (:session-id op-info)
                    :interrupt-id msg-id}))
  (when-let [thread (:thread op-info)]
    (.interrupt thread)))

(defn cancel-filesystem-operation! [op-info]
  (when-let [thread (:thread op-info)]
    (.interrupt thread)))

(defn cancel-cooperative-operation! [op-info]
  (when-let [token (:cancellation-token op-info)]
    (cancel-token! token)))
```

### 5. Tool Integration

#### Tool Wrapper
```clojure
(defn execute-tool-with-cancellation [tool-call context]
  (let [request-id (get-request-id tool-call)
        cancellation-token (create-cancellation-token request-id)]
    (try
      (register-operation! request-id 
                           {:type :tool
                            :cancellation-token cancellation-token
                            :thread (Thread/currentThread)})
      (execute-tool tool-call (assoc context :cancellation-token cancellation-token))
      (finally
        (unregister-operation! request-id)))))
```

#### Cooperative Cancellation
```clojure
(defn check-cancellation! [cancellation-token]
  (when (cancelled? cancellation-token)
    (throw (ex-info "Operation cancelled" {:reason :cancelled}))))

;; Usage in tools
(defn some-long-running-operation [cancellation-token]
  (doseq [item large-collection]
    (check-cancellation! cancellation-token)
    (process-item item)))
```

## Performance Considerations

### Priority Implementation Order

1. **High Priority** (Long-running operations):
   - Performance tools (`profile-cpu`, `profile-alloc`)
   - Eval tools (`eval`, `load-file`)
   - Test tools (`test-all`)
   - Project analysis tools (`lint-project`, `find-function-usages-in-project`)

2. **Medium Priority**:
   - Navigation tools (`call-hierarchy`, `usage-finder`)
   - Refactor tools that modify files

3. **Low Priority**:
   - Quick operations (<1s typical execution)
   - Pure in-memory operations

### Resource Management

- **Graceful Cleanup**: Ensure resources are properly cleaned up on cancellation
- **State Consistency**: Maintain session state consistency across interruptions
- **Thread Safety**: Use atomic operations for cancellation registry updates

## Error Handling

### Exception Patterns

```clojure
(defn handle-cancellation-exception [e]
  (cond
    (instance? InterruptedException e)
    {:status :cancelled :reason "Thread interrupted"}
    
    (and (instance? Exception e)
         (= "Operation cancelled" (.getMessage e)))
    {:status :cancelled :reason "Cooperative cancellation"}
    
    :else
    {:status :error :error (.getMessage e)}))
```

### Logging

```clojure
(defn log-cancellation [request-id reason]
  (log/log! {:level :info :msg "Operation cancelled" 
             :data {:request-id request-id :reason reason}}))
```

## Testing Strategy

### Unit Tests
- Test cancellation registry operations
- Test notification handler registration
- Test cooperative cancellation tokens

### Integration Tests
- Test nREPL interrupt integration
- Test filesystem operation cancellation
- Test race condition handling

### Performance Tests
- Test cancellation response times
- Test resource cleanup efficiency
- Test high-load cancellation scenarios

## Security Considerations

1. **Request ID Validation**: Ensure request IDs are properly validated
2. **DoS Protection**: Prevent cancellation spam attacks
3. **Resource Limits**: Implement reasonable timeouts and limits
4. **Audit Logging**: Log all cancellation attempts for security monitoring

## Future Enhancements

1. **Cancellation Policies**: Configurable cancellation behavior per tool
2. **Partial Results**: Support returning partial results on cancellation
3. **Cancellation Hierarchies**: Support cancelling groups of related operations
4. **Metrics**: Add cancellation metrics for monitoring and debugging

## Conclusion

This cancellation implementation provides comprehensive support for interrupting long-running operations while maintaining the robustness and session consistency that nREPL provides. The three-tier approach (nREPL interrupt, thread interrupt, cooperative cancellation) ensures that all types of operations can be cancelled appropriately based on their execution patterns and requirements.