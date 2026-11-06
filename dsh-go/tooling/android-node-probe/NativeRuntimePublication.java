// SPDX-License-Identifier: AGPL-3.0-only
// Linux JVM ABI fixture for the actual production JNI registration table.
// Tests private filesystem primitives, NOT Android UID/worker/SQLite integrity.
package io.github.ccch1mneyyy.dsh_app.isolation;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class NativeRuntimePublication {
    private native int publishNoReplace(int directory, String source, String target);
    private native int replace(int directory, String source, String target);
    private native int tryDirectoryLease(int directory);
    private native int tryDatabaseLease(int descriptor);
    private native int cleanupBotStages(int directory);
    private native int openRead(int directory, String name);
    private native int openChildDirectory(int directory, String name, boolean create);
    private native int openStage(int directory, String name);
    private native int removeStage(int directory, String name);
    private native int remove(int directory, String name);
    private native int createSnapshotStage(int directory, String token, int slot);
    private native int openSnapshotStageRead(int directory, String token, int slot);
    private native int removeSnapshotStage(int directory, String token, int slot, int held);
    private native int publishSnapshot(int directory, String token, String target, int readonly, long bytes);
    private native int removePublishedSnapshot(int directory, String target, int readonly);

    private static FileDescriptor descriptor(int number) throws Exception {
        if (number < 3) throw new AssertionError("snapshot_fd_invalid");
        FileDescriptor result = new FileDescriptor();
        Field field = FileDescriptor.class.getDeclaredField("fd");
        field.setAccessible(true); field.setInt(result, number);
        return result;
    }
    private static void require(boolean value) { if (!value) throw new AssertionError("snapshot_jni_contract_failed"); }
    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        NativeRuntimePublication kernel = new NativeRuntimePublication();
        int directory = Integer.parseInt(args[1]);
        String token = "d".repeat(32);
        String target = "runtime-state-before-v2-migration-1794000000000-01234567-89ab-4def-8123-456789abcdef.sqlite";
        byte[] payload = "JNI filesystem fixture, not SQLite".getBytes(StandardCharsets.UTF_8);
        require(kernel.createSnapshotStage(directory, "../denied", 0) < 0);
        require(kernel.createSnapshotStage(directory, token, 4) < 0);
        for (int slot = 0; slot < 4; ++slot) {
            int fd = kernel.createSnapshotStage(directory, token, slot);
            if (slot == 0) {
                try (FileOutputStream output = new FileOutputStream(descriptor(fd))) {
                    output.write(payload); output.getFD().sync();
                    require(kernel.publishSnapshot(directory, token, target, fd, payload.length) == 3);
                }
            } else new FileInputStream(descriptor(fd)).close();
        }
        require(kernel.createSnapshotStage(directory, token, 0) < 0);
        int readonly = kernel.openSnapshotStageRead(directory, token, 0);
        require(kernel.publishSnapshot(directory, token, target, readonly, payload.length) == 0);
        require(Arrays.equals(payload, Files.readAllBytes(Path.of(args[2], target))));
        require(kernel.removeSnapshotStage(directory, token, 0, readonly) == 2); // Linux ENOENT, no published-file deletion.
        require(kernel.removePublishedSnapshot(directory, target, readonly) == 0);
        new FileInputStream(descriptor(readonly)).close();
        for (int slot = 1; slot < 4; ++slot) {
            int fd = kernel.openSnapshotStageRead(directory, token, slot);
            require(kernel.removeSnapshotStage(directory, token, slot, fd) == 0);
            new FileInputStream(descriptor(fd)).close();
        }
        System.out.println("PASS: actual production JNI registration and private snapshot methods; Linux JVM only");
    }
}
