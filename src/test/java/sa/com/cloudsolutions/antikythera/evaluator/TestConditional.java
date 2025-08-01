package sa.com.cloudsolutions.antikythera.evaluator;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.mock.MockingRegistry;
import sa.com.cloudsolutions.antikythera.exception.AntikytheraException;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.expr.Expression;

class TestConditional extends TestHelper {

    public static final String SAMPLE_CLASS = "sa.com.cloudsolutions.antikythera.evaluator.Conditional";
    public static final String PERSON_CLASS = "sa.com.cloudsolutions.antikythera.evaluator.Person";
    CompilationUnit cu;

    @BeforeAll
    static void setup() throws IOException {
        Settings.loadConfigMap(new File("src/test/resources/generator-field-tests.yml"));
        AbstractCompiler.preProcess();
        AntikytheraRunTime.reset();
        MockingRegistry.reset();
    }

    @BeforeEach
    void each() {
        cu = AntikytheraRunTime.getCompilationUnit(SAMPLE_CLASS);
        evaluator = EvaluatorFactory.create(SAMPLE_CLASS, SpringEvaluator.class);
        System.setOut(new PrintStream(outContent));
        Branching.clear();
    }


    @Test
    void testExecuteMethod() throws ReflectiveOperationException {
        Evaluator p = EvaluatorFactory.create(PERSON_CLASS, SpringEvaluator.class);
        TypeDeclaration<?> td = AntikytheraRunTime.getTypeDeclaration(p.getClassName()).orElseThrow();
        ConstructorDeclaration cde = td.findFirst(ConstructorDeclaration.class).orElseThrow();
        AntikytheraRunTime.push(new Variable("Hello"));
        p.executeConstructor(cde);

        AntikytheraRunTime.push(new Variable(p));

        MethodDeclaration method = cu.findFirst(MethodDeclaration.class,
                md -> md.getNameAsString().equals("conditional1")).orElseThrow();
        evaluator.executeMethod(method);
        assertEquals("Hello!", outContent.toString());
    }

    @ParameterizedTest
    @CsvSource({"conditional1, The name is null!Antikythera!", "conditional2, Antikythera!The name is null!",
            "conditional3, 1ZERO!", "emptiness1, List is not empty!List is empty!",
            "emptiness2, List is not empty!List is empty!",
            "emptiness3, List is empty!List is not empty!",
            "emptiness4, Set is empty!Set is not empty!",
            "emptiness5, Map is empty!Map is not empty!",
            "stringUtilsString, Not empty!Empty!",
            "notStringUtilsPerson, Not empty!Empty!",
            "stringUtilsPerson, Empty!Not empty!",
            "collectionCheck, Not empty!Empty!",
    })
    void testVisit(String name, String value) throws ReflectiveOperationException {
        ((SpringEvaluator)evaluator).setArgumentGenerator(new DummyArgumentGenerator());

        MethodDeclaration method = cu.findFirst(MethodDeclaration.class,
                md -> md.getNameAsString().equals(name)).orElseThrow();
        evaluator.visit(method);
        assertEquals(value, outContent.toString(), "For parameter: " + name);
    }

    @ParameterizedTest
    @CsvSource({"conditional4, ZERO!Positive!Negative!", "conditional5, ZERO!Three!Two!One!",
            "conditional6, ZERO!Three!Two!One!","conditional7, ZERO!Three!Two!One!",
            "conditional8, ZERO!Three!Two!One!", "smallDiff, One!Nearly 2!",
            "booleanWorks, False!True!", "printMap, Map is empty!Key: 0 -> Value: null",
            "animalFarm, Some animals are more equal!All animals are equal!",
            "whatInstance, Integer: 0!Unknown!"
    })
    void testConditionalsAllPaths(String name, String value) throws ReflectiveOperationException {
        ((SpringEvaluator)evaluator).setArgumentGenerator(new DummyArgumentGenerator());

        MethodDeclaration method = cu.findFirst(MethodDeclaration.class,
                md -> md.getNameAsString().equals(name)).orElseThrow();
        evaluator.visit(method);
        String s = outContent.toString();
        assertEquals(value,s);
    }

    @ParameterizedTest
    @CsvSource({"conditional5, One!","conditional6, One!","conditional7, One!",
            "conditional8, ZERO!", "smallDiff, '',",
    })
    void testConditionals(String name, String value) throws ReflectiveOperationException {
        ((SpringEvaluator)evaluator).setArgumentGenerator(new DummyArgumentGenerator());

        MethodDeclaration method = cu.findFirst(MethodDeclaration.class,
                md -> md.getNameAsString().equals(name)).orElseThrow();

        AntikytheraRunTime.push(new Variable(1));
        evaluator.executeMethod(method);
        String s = outContent.toString();
        assertEquals(value,s);
    }

    @Test
    void testConditional4() throws ReflectiveOperationException {
        Evaluator p = EvaluatorFactory.create(PERSON_CLASS, SpringEvaluator.class);
        ((SpringEvaluator)evaluator).setArgumentGenerator(new DummyArgumentGenerator());

        MethodDeclaration method = cu.findFirst(MethodDeclaration.class,
                md -> md.getNameAsString().equals("conditional4")).orElseThrow();

        AntikytheraRunTime.push(new Variable(p));
        evaluator.executeMethod(method);
        String s = outContent.toString();
        assertEquals("ZERO!",s);
    }

    @Test
    void testBooleanWorks() throws ReflectiveOperationException {
        ((SpringEvaluator)evaluator).setArgumentGenerator(new DummyArgumentGenerator());

        MethodDeclaration method = cu.findFirst(MethodDeclaration.class,
                md -> md.getNameAsString().equals("booleanWorks")).orElseThrow();

        AntikytheraRunTime.push(new Variable(true));
        evaluator.executeMethod(method);
        String s = outContent.toString();
        assertEquals("True!",s);
    }

    @ParameterizedTest
    @CsvSource({"1, One!","2, Two!","3, Three!","4, Guess!"})
    void testSwitchCase(String key, String value) throws ReflectiveOperationException {
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class,
                md -> md.getNameAsString().equals("switchCase1")).orElseThrow();

        AntikytheraRunTime.push(new Variable(Integer.parseInt(key)));
        evaluator.executeMethod(method);
        assertEquals(value, outContent.toString());
    }

    @Test
    void testCombinedConditionForNestedMethod() {
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class,
                md -> md.getNameAsString().equals("nested")).orElseThrow();

        List<ReturnStmt> returnStatements = method.findAll(ReturnStmt.class);
        assertEquals(3, returnStatements.size(), "Expected 3 return statements in the nested method");

        // Test for the first return statement (return "Zero")
        ReturnConditionVisitor visitor1 = new ReturnConditionVisitor(returnStatements.get(0));
        method.accept(visitor1, null);
        Expression combinedCondition1 = BinaryOps.getCombinedCondition(visitor1.getConditions());
        assertEquals("(a == 0) && (a >= 0)", combinedCondition1.toString(), "Incorrect combined condition for 'Zero'");

        // Test for the second return statement (return "Positive")
        ReturnConditionVisitor visitor2 = new ReturnConditionVisitor(returnStatements.get(1));
        method.accept(visitor2, null);
        Expression combinedCondition2 = BinaryOps.getCombinedCondition(visitor2.getConditions());
        assertEquals("(a != 0) && (a >= 0)", combinedCondition2.toString(), "Incorrect combined condition for 'Positive'");

        // Test for the third return statement (return "Negative")
        ReturnConditionVisitor visitor3 = new ReturnConditionVisitor(returnStatements.get(2));
        method.accept(visitor3, null);
        Expression combinedCondition3 = BinaryOps.getCombinedCondition(visitor3.getConditions());
        assertEquals("a < 0", combinedCondition3.toString(), "Incorrect combined condition for 'Negative'");
    }

    @Test
    void testMultivariate() throws ReflectiveOperationException {
        ((SpringEvaluator)evaluator).setArgumentGenerator(new DummyArgumentGenerator());

        MethodDeclaration method = cu.findFirst(MethodDeclaration.class,
                md -> md.getNameAsString().equals("multiVariate")).orElseThrow();

        evaluator.visit(method);
        String s = outContent.toString();
        assertEquals("Bee!Zero!Antikythera!Aargh!",s.replaceAll("\\n",""));
    }


    @ParameterizedTest
    @CsvSource({
            "ternary1, test,  It is not null!",
            "ternary1, null,  It is null!",
            "ternary2, test,  It is not null!",
            "ternary2, null,  It is null!"
    })
    void testTernary(String name, String arg , String value) throws ReflectiveOperationException {
        ((SpringEvaluator)evaluator).setArgumentGenerator(new DummyArgumentGenerator());

        MethodDeclaration method = cu.findFirst(MethodDeclaration.class,
                md -> md.getNameAsString().equals(name)).orElseThrow();

        if (arg.equals("null")) {
            AntikytheraRunTime.push(new Variable(null));
        }
        else {
            AntikytheraRunTime.push(new Variable(arg));
        }
        Variable v = evaluator.executeMethod(method);
        assertEquals(v.getValue(), value);
    }

    @ParameterizedTest
    @CsvSource({"ternary3, It is not null!It is null!", "ternary4, Big!Small!",
            "stringCompare, Donno!Hello!AK!", "fileCompare, Other!tmp!Null!",
            "numberCompare, Other!Two!One!", "drinkable, good!Very good!Not drinkable!",
            "clarendon, Not drinkable!good!", "ternary5, False!True!",
    })
    void testTernaryVisit(String name, String result) throws ReflectiveOperationException {
        ((SpringEvaluator)evaluator).setArgumentGenerator(new DummyArgumentGenerator());

        MethodDeclaration method = cu.findFirst(MethodDeclaration.class,
                md -> md.getNameAsString().equals(name)).orElseThrow();


        evaluator.visit(method);
        String s = outContent.toString();
        assertEquals(result,s.replaceAll("\\n",""));
    }

}

class TestConditionalWithOptional extends TestHelper {
    public static final String SAMPLE_CLASS = "sa.com.cloudsolutions.antikythera.evaluator.Opt";
    CompilationUnit cu;

    @BeforeAll
    static void setup() throws IOException {
        Settings.loadConfigMap(new File("src/test/resources/generator-field-tests.yml"));
        AbstractCompiler.preProcess();
    }

    @BeforeEach
    void each() {
        cu = AntikytheraRunTime.getCompilationUnit(SAMPLE_CLASS);
        evaluator = EvaluatorFactory.create(SAMPLE_CLASS, SpringEvaluator.class);
        System.setOut(new PrintStream(outContent));
    }

    @ParameterizedTest
    @CsvSource({
        "ifEmpty, ID not found\\nID: 1",
        "ifPresent, ID: 1", "binOptionals, x is not 10\\nx is 10",
        "optionalString, ANTIKYTHERA\\nDEFAULT"
    })
    void testOptionals(String methodName, String expectedOutput) throws ReflectiveOperationException, AntikytheraException {
        ((SpringEvaluator)evaluator).setArgumentGenerator(new DummyArgumentGenerator());

        MethodDeclaration method = cu.findFirst(MethodDeclaration.class,
                md -> md.getNameAsString().equals(methodName)).orElseThrow();
        evaluator.visit(method);
        assertEquals(expectedOutput.replace("\\n","\n"), outContent.toString().strip());
    }
}

