package ru.sddp.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.sddp.platform.api.TopologySnapshot;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotRoundTripTest {
    @Test void snapshotSerializesAllEnvironmentsContractsAndMapping() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        UUID source=UUID.randomUUID(), target=UUID.randomUUID(), contract=UUID.randomUUID();
        var mapping=mapper.readTree("{\"transformations\":[{\"type\":\"DIRECT_COPY\",\"from\":\"$.a\",\"to\":\"$.b\"}]}");
        var settings=mapper.readTree("{\"host\":\"example\",\"port\":8080,\"target_endpoint\":\"/\"}");
        var snapshot=new TopologySnapshot(1,"test",List.of(new TopologySnapshot.Node(source,"source",false),new TopologySnapshot.Node(target,"target",true)),List.of(new TopologySnapshot.Contract(contract,"order","v2",mapper.createObjectNode(),null)),List.of(new TopologySnapshot.Connection("DEV",source,target,contract,"REST",settings,mapping),new TopologySnapshot.Connection("PROD",source,target,contract,"REST",settings,mapping)));
        var restored=mapper.readValue(mapper.writeValueAsBytes(snapshot),TopologySnapshot.class);
        assertEquals(2,restored.connections().size());
        assertEquals("v2",restored.contracts().getFirst().version());
        assertEquals(mapping,restored.connections().getFirst().mappingRules());
    }
}
