package org.eclipse.edc.mvd.storage;

import java.io.*;
import java.nio.file.*;

public class FileSystemAssetStore implements AssetStore{
    private final Path root;
    public FileSystemAssetStore(String rootDirectory) throws IOException {
        this.root = Paths.get(rootDirectory);
        Files.createDirectories(root);
    }
    @Override public void save(String id, InputStream in) throws IOException {
        Files.copy(in, root.resolve(id), StandardCopyOption.REPLACE_EXISTING);
    }

    @Override public InputStream load(String id) throws IOException {
        return Files.newInputStream(root.resolve(id));
    }

    @Override public boolean exists(String id) {
        return Files.exists(root.resolve(id));
    }

}