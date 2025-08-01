package sa.com.cloudsolutions.antikythera.evaluator;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TestDummyArgumentGenerator {
    DummyArgumentGenerator dummy = new DummyArgumentGenerator();
    @BeforeAll
    static void setUP() throws IOException {
        Settings.loadConfigMap(new File("src/test/resources/generator-field-tests.yml"));
        AbstractCompiler.reset();
        AbstractCompiler.preProcess();
        AntikytheraRunTime.reset();
    }

    @ParameterizedTest
    @CsvSource({"String, Antikythera", "int, 0", "Boolean, true", "Double, 0.0", "Float, 0.0", "Long, 0"})
    void testGenerateArgument(String type, Object value) throws ReflectiveOperationException {
        MethodDeclaration md = new MethodDeclaration();
        Parameter parameter = new Parameter();
        helper(type, value, md, parameter);
    }

    @ParameterizedTest
    @CsvSource({"String, Antikythera", "int, 0", "Boolean, true", "Double, 0.0", "Float, 0.0", "Long, 0," +
            "List, java.util.ArrayList", "Map, {}", "Set, []"})
    void testGenerateArgumentBody(String type, Object value) throws ReflectiveOperationException{


        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(
                "sa.com.cloudsolutions.antikythera.evaluator.Hello").clone();
        cu.addImport("java.util.Map");
        cu.addImport("java.util.Set");
        MethodDeclaration md = cu.getClassByName("Hello").get().findFirst(MethodDeclaration.class).orElseThrow();
        Parameter parameter = new Parameter();
        md.addParameter(parameter);
        parameter.addAnnotation("RequestBody");

        helper(type, value, md, parameter);
    }

    private void helper(String type, Object value, MethodDeclaration md, Parameter parameter) throws ReflectiveOperationException {
        md.setName("test");

        parameter.setName("First");
        parameter.setType(type);
        md.addParameter(parameter);

        dummy.generateArgument(parameter);
        Variable v = AntikytheraRunTime.pop();
        assertNotNull(v);
        assertEquals(value, v.getValue().toString());
    }

    @ParameterizedTest
    @CsvSource({
        "Integer, 0", "Long, 0",
        "Float, 0.0", "Double, 0.0",
        "Boolean, true",
        "ArrayList, []",
        "HashMap, {}",
        "LinkedList, []"
    })
    void testMockNonPrimitiveParameter(String type, String expectedValue) throws ReflectiveOperationException {
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(
                "sa.com.cloudsolutions.antikythera.evaluator.Hello").clone();
        cu.addImport("java.util." + type);

        MethodDeclaration md = cu.getClassByName("Hello").get().findFirst(MethodDeclaration.class).orElseThrow();
        Parameter parameter = new Parameter();
        md.addParameter(parameter);
        parameter.setName("param");
        parameter.setType(type);

        dummy.generateArgument(parameter);
        Variable v = AntikytheraRunTime.pop();

        assertNotNull(v);
        assertEquals(expectedValue, v.getValue().toString());
    }
}
