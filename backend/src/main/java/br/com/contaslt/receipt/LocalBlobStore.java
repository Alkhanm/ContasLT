package br.com.contaslt.receipt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Comprovantes numa pasta do servidor (uso local: {@code app.storage.dir/receipts}). */
final class LocalBlobStore implements ReceiptBlobStore {

    private final Path dir;

    LocalBlobStore(Path dir) {
        this.dir = dir;
    }

    @Override
    public void put(String name, byte[] bytes) throws IOException {
        Files.createDirectories(dir);
        Files.write(dir.resolve(name), bytes);
    }

    @Override
    public Optional<byte[]> get(String name) throws IOException {
        Path file = dir.resolve(name);
        return Files.exists(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
    }

    @Override
    public int deleteByPrefix(String prefix) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int n = 0;
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (f.getFileName().toString().startsWith(prefix)) {
                    Files.deleteIfExists(f);
                    n++;
                }
            }
        }
        return n;
    }

    @Override
    public String toString() {
        return "pasta " + dir.toAbsolutePath();
    }
}
