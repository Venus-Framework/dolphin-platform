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
package com.canoo.dolphin.server.context;

import com.canoo.dolphin.BeanManager;
import com.canoo.dolphin.event.Subscription;
import com.canoo.dolphin.impl.BeanManagerImpl;
import com.canoo.dolphin.impl.ClassRepositoryImpl;
import com.canoo.dolphin.impl.Converters;
import com.canoo.dolphin.impl.InternalAttributesBean;
import com.canoo.dolphin.impl.PlatformConstants;
import com.canoo.dolphin.impl.PresentationModelBuilderFactory;
import com.canoo.dolphin.impl.collections.ListMapperImpl;
import com.canoo.dolphin.internal.ClassRepository;
import com.canoo.dolphin.internal.EventDispatcher;
import com.canoo.dolphin.internal.collections.ListMapper;
import com.canoo.dolphin.server.DolphinSession;
import com.canoo.dolphin.server.container.ContainerManager;
import com.canoo.dolphin.server.controller.ControllerHandler;
import com.canoo.dolphin.server.controller.ControllerRepository;
import com.canoo.dolphin.server.event.impl.DolphinEventBusImpl;
import com.canoo.dolphin.server.impl.ServerBeanBuilder;
import com.canoo.dolphin.server.impl.ServerBeanBuilderImpl;
import com.canoo.dolphin.server.impl.ServerBeanRepository;
import com.canoo.dolphin.server.impl.ServerBeanRepositoryImpl;
import com.canoo.dolphin.server.impl.ServerControllerActionCallBean;
import com.canoo.dolphin.server.impl.ServerEventDispatcher;
import com.canoo.dolphin.server.impl.ServerPlatformBeanRepository;
import com.canoo.dolphin.server.impl.ServerPresentationModelBuilderFactory;
import com.canoo.dolphin.server.impl.gc.GarbageCollectionCallback;
import com.canoo.dolphin.server.impl.gc.GarbageCollector;
import com.canoo.dolphin.server.impl.gc.Instance;
import com.canoo.dolphin.server.mbean.DolphinContextMBeanRegistry;
import com.canoo.dolphin.util.Assert;
import com.canoo.dolphin.util.Callback;
import org.opendolphin.core.comm.Command;
import org.opendolphin.core.comm.NamedCommand;
import org.opendolphin.core.server.DefaultServerDolphin;
import org.opendolphin.core.server.action.DolphinServerAction;
import org.opendolphin.core.server.comm.ActionRegistry;
import org.opendolphin.core.server.comm.CommandHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This class defines the central entry point for a Dolphin Platform session on the server.
 * Each Dolphin Platform client context on the client side is connected with one {@link DolphinContext}.
 */
public class DolphinContext implements DolphinSessionProvider {

    private static final Logger LOG = LoggerFactory.getLogger(DolphinContext.class);

    private final DefaultServerDolphin dolphin;

    private final ServerBeanRepository beanRepository;

    private final Converters converters;

    private final BeanManager beanManager;

    private final ControllerHandler controllerHandler;

    private final EventDispatcher dispatcher;

    private final DolphinEventBusImpl dolphinEventBus;

    private ServerPlatformBeanRepository platformBeanRepository;

    private final String id;

    private final DolphinContextMBeanRegistry mBeanRegistry;

    private final Callback<DolphinContext> onDestroyCallback;

    private final Callback<DolphinContext> preDestroyCallback;

    private final Subscription mBeanSubscription;

    private final DolphinSession dolphinSession;

    private final GarbageCollector garbageCollector;

    private final IdentityHashMap<Callable, CompletableFuture> runLaterTasks = new IdentityHashMap<>();

    private final Lock runLaterTasksLock = new ReentrantLock();

    public DolphinContext(ContainerManager containerManager, ControllerRepository controllerRepository, OpenDolphinFactory dolphinFactory, DolphinEventBusImpl dolphinEventBus, Callback<DolphinContext> preDestroyCallback, Callback<DolphinContext> onDestroyCallback, DolphinSessionProvider dolphinSessionProvider) {
        Assert.requireNonNull(containerManager, "containerManager");
        Assert.requireNonNull(controllerRepository, "controllerRepository");
        Assert.requireNonNull(dolphinFactory, "dolphinFactory");
        this.preDestroyCallback = Assert.requireNonNull(preDestroyCallback, "preDestroyCallback");
        this.onDestroyCallback = Assert.requireNonNull(onDestroyCallback, "onDestroyCallback");
        this.dolphinEventBus = Assert.requireNonNull(dolphinEventBus, "dolphinEventBus");

        //ID
        id = UUID.randomUUID().toString();

        //Init Open Dolphin
        dolphin = dolphinFactory.create();

        //Init Garbage Collection
        garbageCollector = new GarbageCollector(new GarbageCollectionCallback() {
            @Override
            public void onReject(Set<Instance> instances) {
                for(Instance instance : instances) {
                    beanRepository.onGarbageCollectionRejection(instance.getBean());
                }
            }
        });

        //Init BeanRepository
        dispatcher = new ServerEventDispatcher(dolphin);
        beanRepository = new ServerBeanRepositoryImpl(dolphin, dispatcher, garbageCollector);
        converters = new Converters(beanRepository);

        //Init BeanManager
        final PresentationModelBuilderFactory builderFactory = new ServerPresentationModelBuilderFactory(dolphin);
        final ClassRepository classRepository = new ClassRepositoryImpl(dolphin, converters, builderFactory);
        final ListMapper listMapper = new ListMapperImpl(dolphin, classRepository, beanRepository, builderFactory, dispatcher);
        final ServerBeanBuilder beanBuilder = new ServerBeanBuilderImpl(classRepository, beanRepository, listMapper, builderFactory, dispatcher, garbageCollector);
        beanManager = new BeanManagerImpl(beanRepository, beanBuilder);

        //Init MBean Support
        mBeanRegistry = new DolphinContextMBeanRegistry(id);

        //Init ControllerHandler
        controllerHandler = new ControllerHandler(mBeanRegistry, containerManager, beanBuilder, beanRepository, controllerRepository);

        dolphinSession = new DolphinSessionImpl(id, dolphinSessionProvider, new DolphinSessionRunner() {
            @Override
            public <V> Future<V> runLater(Callable<V> callable) {
                runLaterTasksLock.lock();
                try {
                    CompletableFuture<V> completableFuture = new CompletableFuture<>();
                    runLaterTasks.put(callable, completableFuture);
                    return completableFuture;
                } finally {
                    runLaterTasksLock.unlock();
                }
            }
        });

        //Register commands
        registerDolphinPlatformDefaultCommands();

        mBeanSubscription = mBeanRegistry.registerDolphinContext(dolphinSession, garbageCollector);
    }

    private void registerDolphinPlatformDefaultCommands() {
        dolphin.register(new DolphinServerAction() {
            @Override
            public void registerIn(ActionRegistry registry) {

                registry.register(PlatformConstants.INIT_CONTEXT_COMMAND_NAME, new CommandHandler() {
                    @Override
                    public void handleCommand(Command command, List response) {
                        onInitContext();
                    }
                });

                registry.register(PlatformConstants.DESTROY_CONTEXT_COMMAND_NAME, new CommandHandler() {
                    @Override
                    public void handleCommand(Command command, List response) {
                        onDestroyContext();
                    }
                });

                registry.register(PlatformConstants.REGISTER_CONTROLLER_COMMAND_NAME, new CommandHandler() {
                    @Override
                    public void handleCommand(Command command, List response) {
                        onRegisterController();
                    }
                });

                registry.register(PlatformConstants.DESTROY_CONTROLLER_COMMAND_NAME, new CommandHandler() {
                    @Override
                    public void handleCommand(Command command, List response) {
                        onDestroyController();
                    }
                });

                registry.register(PlatformConstants.CALL_CONTROLLER_ACTION_COMMAND_NAME, new CommandHandler() {
                    @Override
                    public void handleCommand(Command command, List response) {
                        onCallControllerAction();
                    }
                });

                registry.register(PlatformConstants.POLL_EVENT_BUS_COMMAND_NAME, new CommandHandler() {
                    @Override
                    public void handleCommand(Command command, List response) {
                        onPollEventBus();
                    }
                });

                registry.register(PlatformConstants.RELEASE_EVENT_BUS_COMMAND_NAME, new CommandHandler() {
                    @Override
                    public void handleCommand(Command command, List response) {
                        onReleaseEventBus();
                    }
                });

                registry.register(PlatformConstants.GARBAGE_COLLECTION_COMMAND_NAME, new CommandHandler() {
                    @Override
                    public void handleCommand(Command command, List response) {
                        onGarbageCollection();
                    }
                });

                registry.register(PlatformConstants.RUN_LATER_COMMAND_NAME, new CommandHandler() {
                    @Override
                    public void handleCommand(Command command, List response) {
                        onRunLater();
                    }
                });

            }
        });
    }

    private void onInitContext() {
        platformBeanRepository = new ServerPlatformBeanRepository(dolphin, beanRepository, dispatcher, converters);
    }

    private void onDestroyContext() {
        destroy();
    }

    public void destroy() {
        preDestroyCallback.call(this);

        //Deregister from event bus
        dolphinEventBus.unsubscribeSession(getId());

        //Destroy all controllers
        controllerHandler.destroyAllControllers();

        if(mBeanSubscription != null) {
            mBeanSubscription.unsubscribe();
        }

        onDestroyCallback.call(this);
    }

    private void onRunLater() {
        runLaterTasksLock.lock();
        try {
            for(Callable c : runLaterTasks.keySet()) {
                CompletableFuture completableFuture = runLaterTasks.get(c);
                try {
                    Object result = c.call();
                    completableFuture.complete(result);
                } catch (Exception e) {
                    completableFuture.completeExceptionally(e);
                }
            }
            runLaterTasks.clear();
        } finally {
            runLaterTasksLock.unlock();
        }
    }

    private void onRegisterController() {
        final InternalAttributesBean bean = platformBeanRepository.getInternalAttributesBean();
        String controllerId = controllerHandler.createController(bean.getControllerName());
        bean.setControllerId(controllerId);
        Object model = controllerHandler.getControllerModel(controllerId);
        if (model != null) {
            bean.setModel(model);
        }
    }

    private void onDestroyController() {
        final InternalAttributesBean bean = platformBeanRepository.getInternalAttributesBean();
        controllerHandler.destroyController(bean.getControllerId());
    }

    private void onCallControllerAction() {
        if (platformBeanRepository == null) {
            throw new IllegalStateException("An action was called before the init-command was sent.");
        }
        final ServerControllerActionCallBean bean = platformBeanRepository.getControllerActionCallBean();
        try {
            controllerHandler.invokeAction(bean);
        } catch (Exception e) {
            LOG.error("Unexpected exception while invoking action {} on controller {}",
                    bean.getActionName(), bean.getControllerId(), e);
            bean.setError(true);
        }
    }

    private void onReleaseEventBus() {
        dolphinEventBus.release();
    }

    private void onPollEventBus() {
        try {
            dolphinEventBus.longPoll();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void onGarbageCollection() {
        garbageCollector.gc();
    }

    public DefaultServerDolphin getDolphin() {
        return dolphin;
    }

    public BeanManager getBeanManager() {
        return beanManager;
    }

    public String getId() {
        return id;
    }

    public List<Command> handle(List<Command> commands) {
        List<Command> results = new LinkedList<>();
        for (Command command : commands) {
            results.addAll(dolphin.getServerConnector().receive(command));
        }

        NamedCommand runLaterCollectionCommand = new NamedCommand(PlatformConstants.RUN_LATER_COMMAND_NAME);
        results.addAll(dolphin.getServerConnector().receive(runLaterCollectionCommand));

        NamedCommand garbageCollectionCommand = new NamedCommand(PlatformConstants.GARBAGE_COLLECTION_COMMAND_NAME);
        results.addAll(dolphin.getServerConnector().receive(garbageCollectionCommand));

        return results;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DolphinContext that = (DolphinContext) o;

        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    public DolphinSession getCurrentDolphinSession() {
        return dolphinSession;
    }
}
