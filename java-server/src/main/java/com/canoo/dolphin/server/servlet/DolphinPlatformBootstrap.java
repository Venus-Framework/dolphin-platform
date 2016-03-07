/*
 * Copyright 2015-2016 Canoo Engineering AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.canoo.dolphin.server.servlet;

import com.canoo.dolphin.server.container.ContainerManager;
import com.canoo.dolphin.server.context.DolphinContextCleaner;
import com.canoo.dolphin.server.context.DolphinContextHandler;
import com.canoo.dolphin.server.controller.ControllerRepository;
import org.opendolphin.server.adapter.InvalidationServlet;

import javax.servlet.DispatcherType;
import javax.servlet.ServletContext;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.ServiceLoader;

public class DolphinPlatformBootstrap {

    public static final String DOLPHIN_SERVLET_NAME = "dolphin-platform-servlet";

    public static final String DOLPHIN_CROSS_SITE_FILTER_NAME = "dolphinCrossSiteFilter";

    public static final String DOLPHIN_INVALIDATION_SERVLET_NAME = "dolphin-platform-invalidation-servlet";

    public static final String DEFAULT_DOLPHIN_SERVLET_MAPPING = "/dolphin";

    public static final String DEFAULT_DOLPHIN_INVALIDATION_SERVLET_MAPPING = "/dolphininvalidate";

    private String dolphinServletMapping;

    private String dolphinInvalidationServletMapping;

    public DolphinPlatformBootstrap() {
        this.dolphinServletMapping = DEFAULT_DOLPHIN_SERVLET_MAPPING;
        this.dolphinInvalidationServletMapping = DEFAULT_DOLPHIN_INVALIDATION_SERVLET_MAPPING;
    }

    public void onStartup(ServletContext servletContext) {
        ContainerManager containerManager = null;
        ServiceLoader<ContainerManager> serviceLoader = ServiceLoader.load(ContainerManager.class);
        Iterator<ContainerManager> serviceIterator = serviceLoader.iterator();
        if (serviceIterator.hasNext()) {
            containerManager = serviceIterator.next();
            if (serviceIterator.hasNext()) {
                throw new RuntimeException("More than 1 " + ContainerManager.class + " found!");
            }
        } else {
            throw new RuntimeException("No " + ContainerManager.class + " found!");
        }
        containerManager.init(servletContext);

        ControllerRepository controllerRepository = new ControllerRepository();

        DolphinContextHandler dolphinContextHandler = new DolphinContextHandler(containerManager, controllerRepository);

        servletContext.addServlet(DOLPHIN_SERVLET_NAME, new DolphinPlatformServlet(dolphinContextHandler)).addMapping(dolphinServletMapping);
        servletContext.addServlet(DOLPHIN_INVALIDATION_SERVLET_NAME, new InvalidationServlet()).addMapping(dolphinInvalidationServletMapping);
        servletContext.addFilter(DOLPHIN_CROSS_SITE_FILTER_NAME, new CrossSiteOriginFilter()).addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), true, "/*");

        servletContext.addListener(new DolphinContextCleaner(dolphinContextHandler));
    }

}
