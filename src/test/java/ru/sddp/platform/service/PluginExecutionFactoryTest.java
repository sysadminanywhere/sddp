package ru.sddp.platform.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import ru.sddp.platform.api.CodegenModels.PluginDescriptor;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PluginExecutionFactoryTest {
    @Test void resolvesImageOnlyWhenExactlyOneExecutorIsActive() {
        @SuppressWarnings("unchecked") ObjectProvider<PluginExecutor> providers = mock(ObjectProvider.class);
        PluginExecutor executor = mock(PluginExecutor.class);
        when(providers.orderedStream()).thenAnswer(call -> Stream.of(executor));
        var factory = new PluginExecutionFactory(providers);
        assertSame(executor, factory.resolve(new PluginDescriptor("JAVA", "DOCKER", "image")));
        assertThrows(IllegalArgumentException.class, () -> factory.resolve(new PluginDescriptor("JAVA", "CLI", "executable")));
        when(providers.orderedStream()).thenAnswer(call -> Stream.empty());
        assertThrows(IllegalStateException.class, () -> factory.resolve(new PluginDescriptor("JAVA", "DOCKER", "image")));
        when(providers.orderedStream()).thenAnswer(call -> Stream.of(executor, executor));
        assertThrows(IllegalStateException.class, () -> factory.resolve(new PluginDescriptor("JAVA", "DOCKER", "image")));
    }
}
