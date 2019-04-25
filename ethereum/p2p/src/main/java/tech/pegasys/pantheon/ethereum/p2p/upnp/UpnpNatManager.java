/*
 * Copyright 2018 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.pantheon.ethereum.p2p.upnp;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jupnp.DefaultUpnpServiceConfiguration;
import org.jupnp.UpnpService;
import org.jupnp.UpnpServiceImpl;
import org.jupnp.model.action.ActionInvocation;
import org.jupnp.model.message.UpnpResponse;
import org.jupnp.model.message.header.STAllHeader;
import org.jupnp.model.meta.Device;
import org.jupnp.model.meta.Service;
import org.jupnp.registry.DefaultRegistryListener;
import org.jupnp.registry.Registry;
import org.jupnp.registry.RegistryListener;
import org.jupnp.support.igd.callback.GetExternalIP;
import org.jupnp.support.igd.callback.GetStatusInfo;
import org.jupnp.support.igd.callback.PortMappingAdd;
import org.jupnp.support.model.Connection;
import org.jupnp.support.model.PortMapping;

@SuppressWarnings("rawtypes")
public class UpnpNatManager {
  protected static final Logger LOG = LogManager.getLogger();

  public static final String SERVICE_DEFAULT_NAMESPACE = "schemas-upnp-org";
  public static final String SERVICE_TYPE_WAN_IP_CONNECTION = "WANIPConnection";

  boolean started = false;
  UpnpService upnpService = null;
  RegistryListener registryListener = null;

  Map<String, Service> recognizedServices;

  /** Empty constructor. Creates in instance of UpnpServiceImpl. */
  public UpnpNatManager() {
    this(new UpnpServiceImpl(new DefaultUpnpServiceConfiguration()));
  }

  /**
   * Constructor
   *
   * @param service is the desired instance of UpnpService.
   */
  public UpnpNatManager(final UpnpService service) {
    upnpService = service;

    // registry listener to observe new devices and look for specific services
    registryListener =
        new DefaultRegistryListener() {
          @Override
          @SuppressWarnings("rawtypes")
          public void deviceAdded(final Registry registry, final Device device) {
            LOG.debug("UPnP Device discovered: " + device.getDetails().getFriendlyName());
            inspectDeviceRecursive(device, recognizedServices.keySet());
          }
        };

    // prime our recognizedServices map so we can use its key-set later
    recognizedServices = new HashMap<>();
    recognizedServices.put(SERVICE_TYPE_WAN_IP_CONNECTION, null);
  }

  /**
   * Start the manager. Must not be in started state.
   *
   * @throws IllegalStateException if already started.
   */
  public void start() {
    if (started) {
      throw new IllegalStateException("Cannot start already-started service");
    }

    LOG.info("starting upnp service...");
    upnpService.startup();
    upnpService.getRegistry().addListener(registryListener);

    // TODO: does jupnp do this automatically?
    upnpService.getControlPoint().search(new STAllHeader());

    started = true;
  }

  /**
   * Stop the manager. Must not be in stopped state.
   *
   * @throws IllegalStateException if stopped.
   */
  public void stop() {
    if (started) {
      throw new IllegalStateException("Cannot stop already-stopped service");
    }
    upnpService.getRegistry().removeListener(registryListener);
    upnpService.shutdown();

    started = false;
  }

  /**
   * Returns the first of the discovered services of the given type, if any.
   *
   * @return the first instance of the given type, or null if none
   */
  @SuppressWarnings("rawtypes")
  public Service getService(final String type) {
    return recognizedServices.get(type);
  }

  /**
   * Get the discovered WANIPConnection service, if any.
   *
   * @return the WANIPConnection Service if we have found it, or null.
   */
  @SuppressWarnings("rawtypes")
  public Service getWANIPConnectionService() {
    return getService(SERVICE_TYPE_WAN_IP_CONNECTION);
  }

  /**
   * Returns a CompletableFuture that will wait for the given service type to be discovered
   *
   * @return future that will return the desired service once it is discovered, or null if the
   *     future is cancelled.
   */
  @SuppressWarnings("rawtypes")
  public CompletableFuture<Service> discoverService(final String serviceType) {

    return CompletableFuture.supplyAsync(
        () -> {

          // wait until our thread is interrupted (assume future was cancelled)
          // or we discover the service
          while (!Thread.currentThread().isInterrupted()) {
            Service service = getService(serviceType);
            if (null != service) {
              return service;
            } else {
              try {
                Thread.sleep(100);
              } catch (InterruptedException e) {
                // fall back through to "isInterrupted() check"
              }
            }
          }
          return null;
        });
  }

  /**
   * Sends a UPnP request to the discovered IGD for the external ip address.
   *
   * @return A CompletableFuture that can be used to query the result (or error).
   */
  public CompletableFuture<String> queryExternalIPAddress() {

    CompletableFuture<String> upnpQueryFuture = new CompletableFuture<>();

    return discoverService(SERVICE_TYPE_WAN_IP_CONNECTION)
        .thenCompose(
            service -> {

              // our query, which will be handled asynchronously by the jupnp library
              GetExternalIP callback =
                  new GetExternalIP(service) {
                    @Override
                    protected void success(final String result) {
                      upnpQueryFuture.complete(result);
                    }

                    @Override
                    @SuppressWarnings("rawtypes")
                    public void failure(
                        final ActionInvocation invocation,
                        final UpnpResponse operation,
                        final String msg) {
                      upnpQueryFuture.completeExceptionally(new Exception(msg));
                    }
                  };
              upnpService.getControlPoint().execute(callback);

              return upnpQueryFuture;
            });
  }

  /**
   * Sends a UPnP request to the discovered IGD to request status info.
   *
   * @return A CompletableFuture that can be used to query the result (or error).
   */
  public CompletableFuture<Connection.StatusInfo> queryStatusInfo() {

    CompletableFuture<Connection.StatusInfo> upnpQueryFuture = new CompletableFuture<>();

    return discoverService(SERVICE_TYPE_WAN_IP_CONNECTION)
        .thenCompose(
            service -> {
              GetStatusInfo callback =
                  new GetStatusInfo(service) {
                    @Override
                    public void success(final Connection.StatusInfo statusInfo) {
                      upnpQueryFuture.complete(statusInfo);
                    }

                    @Override
                    @SuppressWarnings("rawtypes")
                    public void failure(
                        final ActionInvocation invocation,
                        final UpnpResponse operation,
                        final String msg) {
                      upnpQueryFuture.completeExceptionally(new Exception(msg));
                    }
                  };
              upnpService.getControlPoint().execute(callback);

              return upnpQueryFuture;
            });
  }

  /**
   * Sends a UPnP request to the discovered IGD to request a port forward.
   *
   * @return A CompletableFuture that can be used to query the result (or error).
   */
  public CompletableFuture<String> requestPortForward(final PortMapping portMapping) {

    CompletableFuture<String> upnpQueryFuture = new CompletableFuture<>();

    return discoverService(SERVICE_TYPE_WAN_IP_CONNECTION)
        .thenCompose(
            service -> {

              // our query, which will be handled asynchronously by the jupnp library
              PortMappingAdd callback =
                  new PortMappingAdd(service, portMapping) {
                    @Override
                    @SuppressWarnings("rawtypes")
                    public void success(final ActionInvocation invocation) {
                      // TODO: return value here?
                      upnpQueryFuture.complete("TODO");
                    }

                    @Override
                    @SuppressWarnings("rawtypes")
                    public void failure(
                        final ActionInvocation invocation,
                        final UpnpResponse operation,
                        final String msg) {
                      upnpQueryFuture.completeExceptionally(new Exception(msg));
                    }
                  };
              upnpService.getControlPoint().execute(callback);

              return upnpQueryFuture;
            });
  }

  /** Recursively crawls the given device to look for specific services. */
  @SuppressWarnings("rawtypes")
  protected void inspectDeviceRecursive(final Device device, final Set<String> serviceIds) {
    for (Service service : device.getServices()) {
      String serviceType = service.getServiceType().getType();
      if (serviceIds.contains(serviceType)) {
        // TODO: handle case where service is already "recognized" as this could lead to
        // some odd bugs
        recognizedServices.put(serviceType, service);
        LOG.info("Discovered service " + serviceType);
      }
    }
    for (Device subDevice : device.getEmbeddedDevices()) {
      inspectDeviceRecursive(subDevice, serviceIds);
    }
  }

  /** Print the devices and services known to the registry in a hierarchical fashion */
  @SuppressWarnings("rawtypes")
  public void printRegistryContents() {
    System.out.println("Devices known to registry:");
    for (Device device : upnpService.getRegistry().getDevices()) {
      printDeviceRecursive(device, "");
    }
  }

  /** Recursively print out the devices and services known to the registry */
  @SuppressWarnings("rawtypes")
  public void printDeviceRecursive(final Device device, final String indent) {
    String nextIndent = "|    ";
    System.out.println(indent + "├-- device: " + device.getDetails().getFriendlyName());
    System.out.println(indent + nextIndent + "├-- id:           " + device.getIdentity());
    System.out.println(
        indent
            + nextIndent
            + "├-- manufacturer: "
            + device.getDetails().getManufacturerDetails().getManufacturer());
    System.out.println(
        indent
            + nextIndent
            + "├-- model:        "
            + device.getDetails().getModelDetails().getModelName()
            + " - "
            + device.getDetails().getModelDetails().getModelNumber()
            + " - "
            + device.getDetails().getModelDetails().getModelDescription());
    System.out.println(
        indent + nextIndent + "├-- serial:       " + device.getDetails().getSerialNumber());
    System.out.println(
        indent
            + nextIndent
            + "├-- uda version:  "
            + device.getVersion().getMajor()
            + "."
            + device.getVersion().getMinor());
    System.out.println(indent + nextIndent + "├-- type:         " + device.getType());
    for (Service service : device.getServices()) {
      System.out.println(indent + nextIndent + "├-- service:");
      System.out.println(indent + nextIndent + nextIndent + "├-- id:   " + service.getServiceId());
      System.out.println(
          indent + nextIndent + nextIndent + "├-- type: " + service.getServiceType());
    }
    for (Device subDevice : device.getEmbeddedDevices()) {
      printDeviceRecursive(subDevice, (indent + nextIndent));
    }
  }
}
