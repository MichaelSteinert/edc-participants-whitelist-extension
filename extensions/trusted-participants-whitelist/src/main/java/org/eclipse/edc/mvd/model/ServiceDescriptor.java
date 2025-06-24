package org.eclipse.edc.mvd.model;

import java.util.Objects;

/** Minimal metadata that a provider publishes for an anonymisation service. */
public class ServiceDescriptor {
    private String id;          // unique identifier – e.g. “mask-title”
    private String name;        // human-readable label for UI dropdown
    private String endpoint;    // where the trustee/consumer can POST data for processing

    public ServiceDescriptor() { }
    public ServiceDescriptor(String id, String name, String endpoint) {
        this.id = id; this.name = name; this.endpoint = endpoint;
    }

    public ServiceDescriptor(String s, String s1) {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    @Override public boolean equals(Object o){
        if(this == o) return true;
        if(!(o instanceof ServiceDescriptor that)) return false;
        return Objects.equals(id, that.id);
    }
    @Override public int hashCode(){ return Objects.hash(id); }
}
