package org.eclipse.edc.mvd.service;

import java.io.InputStream;

public interface ServiceProcessor {
    InputStream apply(InputStream in) throws Exception;
}
