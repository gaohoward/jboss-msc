/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.msc.registry;

import org.jboss.msc.service.AbstractServiceListener;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceListener;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ValueInjection;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jboss.msc.value.Value;

/**
 * Service registry capable of installing batches of services and enforcing dependency order.
 *
 * @author John Bailey
 * @author Jason T. Greene
 */
class ServiceRegistryImpl implements ServiceRegistry {
    private final ConcurrentMap<ServiceName, ServiceController<?>> registry = new ConcurrentHashMap<ServiceName, ServiceController<?>>();

    private final ServiceContainer serviceContainer;

    public ServiceRegistryImpl(ServiceContainer serviceContainer) {
        this.serviceContainer = serviceContainer;
    }

    public BatchBuilderImpl batchBuilder() {
        return new BatchBuilderImpl(this);
    }

    /**
     * Install a collection of service definitions into the registry.  Will install the services
     * in dependency order.
     *
     * @param serviceBatch The service batch to install
     * @throws ServiceRegistryException If any problems occur resolving the dependencies or adding to the registry.
     */
    void install(final BatchBuilderImpl serviceBatch) throws ServiceRegistryException {
        try {
            resolve(serviceBatch.getBatchServices(), serviceBatch.getListeners());
        } catch (ResolutionException e) {
            throw new ServiceRegistryException("Failed to resolve dependencies", e);
        }
    }

    private void resolve(final Map<ServiceName, BatchServiceBuilderImpl<?>> services, final Set<ServiceListener<Object>> batchListeners) throws ServiceRegistryException {
        for (BatchServiceBuilderImpl<?> batchEntry : services.values()) {
            if(!batchEntry.processed)
                doResolve(batchEntry, services, batchListeners);
        }
    }

    @SuppressWarnings({ "unchecked" })
    private <T> void doResolve(BatchServiceBuilderImpl<T> entry, final Map<ServiceName, BatchServiceBuilderImpl<?>> services, final Set<ServiceListener<Object>> batchListeners) throws ServiceRegistryException {
        outer:
        while (entry != null) {
            final Value<? extends Service<T>> serviceValue = entry.getServiceValue();

            ServiceBuilder<T> builder;
            if ((builder = entry.builder) == null) {
                builder = entry.builder = serviceContainer.buildService(serviceValue);
            }

            final ServiceName[] deps = entry.getDependencies();
            final ServiceName name = entry.getName();

            while (entry.i < deps.length) {
                final ServiceName dependencyName = deps[entry.i];

                ServiceController<?> dependencyController = registry.get(dependencyName);
                if (dependencyController == null) {
                    final BatchServiceBuilderImpl dependencyEntry = services.get(dependencyName);
                    if (dependencyEntry == null)
                        throw new MissingDependencyException("Missing dependency: " + name + " depends on " + dependencyName + " which can not be found");

                    // Backup the last position, so that we can unroll
                    assert dependencyEntry.prev == null;
                    dependencyEntry.prev = entry;

                    entry.visited = true;
                    entry = dependencyEntry;

                    if (entry.visited)
                        throw new CircularDependencyException("Circular dependency discovered: " + name);

                    continue outer;
                }

                // Either the dep already exists, or we are unrolling and just created it
                builder.addDependency(dependencyController);
                entry.i++;
            }

            // We are resolved.  Lets install
            builder.addListener(new ServiceUnregisterListener(name));

            for(ServiceListener<Object> listener : batchListeners) {
                builder.addListener(listener);
            }

            for(ServiceListener<? super T> listener : entry.getListeners()) {
                builder.addListener(listener);
            }


            for(BatchInjectionBuilderImpl injection : entry.getInjections()) {
                builder.addValueInjection(
                        valueInjection(serviceValue, builder, injection)
                );
            }

            final ServiceController<?> serviceController = builder.create();
            if (registry.putIfAbsent(name, serviceController) != null) {
                throw new DuplicateServiceException("Duplicate service name provided: " + name);
            }

            // Cleanup
            entry.builder = null;
            BatchServiceBuilderImpl prev = entry.prev;
            entry.prev = null;

            // Unroll!
            entry.processed = true;
            entry.visited = false;
            entry = prev;
        }
    }

    @SuppressWarnings({ "unchecked" })
    private <T> ValueInjection<T> valueInjection(final Value<? extends Service<T>> serviceValue, final ServiceBuilder<T> builder, final BatchInjectionBuilderImpl injection) {
        return new ValueInjection(
                injection.getSource().getValue((Value)serviceValue, builder, this),
                injection.getDestination().getInjector((Value)serviceValue, builder, this)
        );
    }

    public ServiceController<?> getRequiredService(final ServiceName serviceName) throws ServiceNotFoundException {
        final ServiceController<?> controller = getService(serviceName);
        if (controller == null) {
            throw new ServiceNotFoundException("Service " + serviceName + " not found");
        }
        return controller;
    }

    public ServiceController<?> getService(final ServiceName serviceName) {
        return registry.get(serviceName);
    }

    private class ServiceUnregisterListener extends AbstractServiceListener<Object> {
        private final ServiceName serviceName;

        private ServiceUnregisterListener(ServiceName serviceName) {
            this.serviceName = serviceName;
        }

        @Override
        public void serviceRemoved(ServiceController serviceController) {
            if(!registry.remove(serviceName, serviceController))
                throw new RuntimeException("Removed service [" + serviceName + "] was not unregistered");
        }
    }
}
