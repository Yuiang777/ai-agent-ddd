package cn.apple.test.domain;

import cn.apple.application.agent.AiClientRuntimeService;
import cn.apple.domain.agent.model.entity.ArmoryCommandEntity;
import cn.apple.domain.agent.model.valobj.AiAgentEnumVO;
import cn.apple.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import cn.applesheep.wrench.design.framework.tree.StrategyHandler;
import org.junit.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.support.GenericApplicationContext;
import java.lang.reflect.Proxy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class AiClientRuntimeConcurrencyTest {
    @Test
    public void concurrentFirstRequestsAssembleClientOnlyOnce() throws Exception {
        CountDownLatch firstLookups = new CountDownLatch(2);
        AtomicInteger lookups = new AtomicInteger();
        AtomicInteger assemblies = new AtomicInteger();
        String beanName = AiAgentEnumVO.AI_CLIENT.getBeanName("test-client");
        ChatClient client = (ChatClient) Proxy.newProxyInstance(ChatClient.class.getClassLoader(),
                new Class<?>[]{ChatClient.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException("No real model calls in this test");
                });
        try (GenericApplicationContext context = new GenericApplicationContext() {
            @Override
            public boolean containsBean(String name) {
                boolean present = super.containsBean(name);
                if (beanName.equals(name) && lookups.incrementAndGet() <= 2) {
                    firstLookups.countDown();
                    try {
                        if (!firstLookups.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("Both callers must observe an unassembled client");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(error);
                    }
                }
                return present;
            }
        }) {
            context.refresh();
            @SuppressWarnings("unchecked")
            StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> handler =
                    (StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String>)
                    Proxy.newProxyInstance(StrategyHandler.class.getClassLoader(),
                            new Class<?>[]{StrategyHandler.class}, (proxy, method, args) -> {
                                if (!"apply".equals(method.getName())) throw new AssertionError(method.getName());
                                assemblies.incrementAndGet();
                                context.getBeanFactory().registerSingleton(beanName, client);
                                return "assembled";
                            });
            DefaultArmoryStrategyFactory factory = new DefaultArmoryStrategyFactory(null) {
                @Override
                public StrategyHandler<ArmoryCommandEntity, DynamicContext, String> armoryStrategyHandler() {
                    return handler;
                }
            };
            AiClientRuntimeService runtime = new AiClientRuntimeService(factory, context, true, 3, 3, 30000);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                var first = workers.submit(() -> runtime.getChatClient("test-client"));
                var second = workers.submit(() -> runtime.getChatClient("test-client"));
                assertSame(client, first.get(10, TimeUnit.SECONDS));
                assertSame(client, second.get(10, TimeUnit.SECONDS));
                assertEquals(1, assemblies.get());
                assertSame(client, runtime.getChatClient("test-client"));
                assertEquals(1, assemblies.get());
            } finally {
                workers.shutdownNow();
            }
        }
    }
}
