// SPDX-License-Identifier: AGPL-3.0-only
// Host-only JNI ABI fixture. Same binary class/method signatures as Kotlin;
// not an Android service, isolated UID check or product launcher.
package io.github.ccch1mneyyy.dsh_app.isolation;

public final class NativeSqliteBackupBindings {
    private native long[] run(int[] descriptors, int uid, int pid, long maxBytes, int timeoutMs);
    private native void cancel();
    private static void require(boolean value) {
        if (!value) throw new AssertionError("native_backup_jni_contract_failed");
    }
    public static void main(String[] args) {
        System.load(args[0]);
        NativeSqliteBackupBindings bridge = new NativeSqliteBackupBindings();
        String mode = args[1];
        int uid = Integer.parseInt(args[2]);
        int pid = Integer.parseInt(args[3]);
        int[] fds = new int[8];
        for (int i = 0; i < 8; ++i) fds[i] = Integer.parseInt(args[i + 4]);
        if (mode.equals("cancel")) bridge.cancel();
        if (mode.equals("shape")) fds = new int[7];
        if (mode.equals("null")) fds = null;
        if (mode.equals("uid")) uid = -1;
        if (mode.equals("pid")) pid = (int) ProcessHandle.current().pid();
        long[] reply = bridge.run(fds, uid, pid, 65536, 5000);
        require(reply != null && reply.length == 3);
        if (mode.equals("copy")) require(reply[0] == 0 && reply[1] == 0 && reply[2] > 0 && reply[2] <= 65536);
        else if (mode.equals("cancel")) require(reply[0] == 5 && reply[1] == 7 && reply[2] == 0);
        else if (mode.equals("pid")) require(reply[0] == 1 && reply[1] == 1 && reply[2] == 0);
        else require(reply[0] == 2 && reply[1] == 1 && reply[2] == 0);
        // Claim is consumed even by null/bad input. Neither cancellation nor a
        // second invocation resets a process or revokes registry descriptors.
        bridge.cancel();
        reply = bridge.run(fds, uid, pid, 65536, 5000);
        require(reply.length == 3 && reply[0] == 1 && reply[1] == 1 && reply[2] == 0);
    }
}
