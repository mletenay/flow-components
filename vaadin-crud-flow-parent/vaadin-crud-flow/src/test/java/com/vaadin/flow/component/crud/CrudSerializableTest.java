package com.vaadin.flow.component.crud;

import com.vaadin.flow.testutil.ClassesSerializableTest;

import java.util.stream.Stream;

public class CrudSerializableTest extends ClassesSerializableTest {

    @Override
    protected Stream<String> getExcludedPatterns() {
        return Stream.concat(super.getExcludedPatterns(),
                Stream.of("com\\.vaadin\\.flow\\.component\\.treegrid\\.TreeGrid"));
    }
}
