package com.salesforce.jw.parsers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.salesforce.jw.parsers.cix.CIXPipeline;
import com.salesforce.jw.parsers.tekton.TektonPipeline;
import com.salesforce.jw.steps.WorkflowProtos;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PipelineParserTest {
    @ParameterizedTest
    @EnumSource(value = WorkflowProtos.Workflow.Type.class, names = { "CIX", "TEKTON_PIPELINE" })
    void testWorkflowType(WorkflowProtos.Workflow.Type wfType) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        PipelineParser pipeline = mapper.readValue(
                ClassLoader.getSystemClassLoader().getResourceAsStream("cix-examples/simple-cix.yaml"), CIXPipeline.class);
        if (wfType.equals(WorkflowProtos.Workflow.Type.TEKTON_PIPELINE)) {
            pipeline = mapper.readValue(
                    ClassLoader.getSystemClassLoader().getResourceAsStream("tekton-examples/simple-tekton.yaml"), TektonPipeline.class);
        }
        WorkflowProtos.Workflow wf = pipeline.toWorkflowProto(Optional.empty());
        assertEquals(wfType, wf.getType());
    }
}
