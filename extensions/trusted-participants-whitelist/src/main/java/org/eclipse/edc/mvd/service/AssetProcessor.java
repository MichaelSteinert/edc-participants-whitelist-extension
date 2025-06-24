package org.eclipse.edc.mvd.service;

import java.io.IOException;
import java.io.InputStream;

/** SPI for post‑processing the asset payload before forwarding to the consumer. */
public interface AssetProcessor {
    InputStream process(InputStream in) throws IOException;
}