package org.eclipse.edc.mvd.service;

import org.eclipse.edc.mvd.model.ServiceDescriptor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps the registered services of *this* connector. */
public final class ServiceRegistry {
    /* what the UI needs                                                   */
    private static final Map<String, ServiceDescriptor> DESCRIPTORS = new ConcurrentHashMap<>();

    /* what the TransferController executes                                */
    private static final Map<String, ServiceProcessor>  PROCESSORS  = new ConcurrentHashMap<>();

    private ServiceRegistry() { }

    /* ---------- bootstrap   ------------------------------------------- */

    static {
        /* built-in service: mask-title */
        PROCESSORS.put("mask-title", new MaskTitleProcessor());

        DESCRIPTORS.put("mask-title",
                new ServiceDescriptor("mask-title",
                        "Replace every JSON field “title” with \"xxx\""));
    }

    /* ---------- descriptor API (provider + UI) ------------------------ */

    public static void add(ServiceDescriptor d)           { DESCRIPTORS.put(d.getId(), d); }
    public static boolean remove(String id)               { return DESCRIPTORS.remove(id) != null; }
    public static List<ServiceDescriptor> list()          { return List.copyOf(DESCRIPTORS.values()); }
    public static Optional<ServiceDescriptor> get(String id){ return Optional.ofNullable(DESCRIPTORS.get(id)); }

    /* ---------- processor lookup (pull-transfer) ---------------------- */

    public static Optional<ServiceProcessor> processor(String id){
        return Optional.ofNullable(PROCESSORS.get(id));
    }
}
