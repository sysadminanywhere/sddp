package ru.sddp.platform.service;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.sddp.platform.api.TopologySnapshot;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotRoundTripTest {
    @Test void snapshotSerializesAllEnvironmentsApisAndMapping() throws Exception {
        ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        UUID source=UUID.randomUUID(), target=UUID.randomUUID(), sourceApi=UUID.randomUUID(), targetApi=UUID.randomUUID();
        var mapping=mapper.readTree("{\"transformations\":[{\"type\":\"DIRECT_COPY\",\"from\":\"$.a\",\"to\":\"$.b\"}]}");
        var settings=mapper.readTree("{\"host\":\"example\",\"port\":8080,\"target_endpoint\":\"/\"}");
        var schema=mapper.readTree("{\"type\":\"object\",\"properties\":{}}");
        var snapshot=new TopologySnapshot(1,"test",
            List.of(new TopologySnapshot.Environment("DEV", "Development", 10, false), new TopologySnapshot.Environment("PROD", "Production", 20, true)),
            List.of(new TopologySnapshot.Service(source,"source","CORE",null,10,20),new TopologySnapshot.Service(target,"target","EXTERNAL",null,null,null)),
            List.of(new TopologySnapshot.Api(sourceApi,source,"createOrder","1",null,"REQUEST_RESPONSE",schema,null),
                new TopologySnapshot.Api(targetApi,target,"createPayment","2",null,"REQUEST_RESPONSE",schema,null)),
            List.of(new TopologySnapshot.Connection("DEV",source,target,sourceApi,targetApi,"REST",settings,mapping),
                new TopologySnapshot.Connection("PROD",source,target,sourceApi,targetApi,"REST",settings,mapping)));
        var restored=mapper.readValue(mapper.writeValueAsBytes(snapshot),TopologySnapshot.class);
        assertEquals(1,restored.formatVersion());
        assertEquals(2,restored.connections().size());
        assertEquals("2",restored.apis().getLast().version());
        assertEquals(mapping,restored.connections().getFirst().mappingRules());
    }
}
