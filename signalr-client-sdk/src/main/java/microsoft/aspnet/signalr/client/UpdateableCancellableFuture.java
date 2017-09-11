/*
Copyright (c) Microsoft Open Technologies, Inc.
All Rights Reserved
See License.txt in the project root for license information.
*/

package microsoft.aspnet.signalr.client;

/**
 * An updateable SignalRFuture that, when cancelled, triggers cancellation on an
 * internal instance
 */
public class UpdateableCancellableFuture<V> extends SignalRFuture<V> {
    private SignalRFuture<?> mFuture = null;

    private final Object mSync = new Object();

    public UpdateableCancellableFuture(SignalRFuture<?> token) {
        mFuture = token;
    }

    public void setFuture(SignalRFuture<?> token) {
        synchronized (mSync) {
            mFuture = token;
        }

        if (isCancelled()) {
            if (mFuture != null) {
                mFuture.cancel();
            }
        }
    }

    @Override
    public void cancel() {
        synchronized (mSync) {
            super.cancel();
            if (mFuture != null) {
                mFuture.cancel();
                mFuture = null;
            }
        }
    }

   public boolean innerErrorWasTriggered() {
        return mFuture.errorWasTriggered();
   }
}
