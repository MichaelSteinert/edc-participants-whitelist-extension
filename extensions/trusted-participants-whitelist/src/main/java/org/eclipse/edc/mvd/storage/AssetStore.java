package org.eclipse.edc.mvd.storage;

import java.io.IOException;
import java.io.InputStream;

public interface AssetStore {
    void save(String id, InputStream in) throws IOException;
    InputStream load (String id) throws IOException;
    boolean exists(String id);
}