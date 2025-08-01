package sa.com.cloudsolutions.antikythera.evaluator;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithArguments;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import net.bytebuddy.ByteBuddy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sa.com.cloudsolutions.antikythera.depsolver.ClassProcessor;
import sa.com.cloudsolutions.antikythera.evaluator.functional.FPEvaluator;
import sa.com.cloudsolutions.antikythera.evaluator.functional.FunctionEvaluator;
import sa.com.cloudsolutions.antikythera.evaluator.functional.FunctionalConverter;
import sa.com.cloudsolutions.antikythera.evaluator.functional.SupplierEvaluator;
import sa.com.cloudsolutions.antikythera.evaluator.logging.AKLogger;
import sa.com.cloudsolutions.antikythera.evaluator.mock.MockingRegistry;
import sa.com.cloudsolutions.antikythera.exception.AUTException;
import sa.com.cloudsolutions.antikythera.exception.AntikytheraException;
import sa.com.cloudsolutions.antikythera.exception.EvaluatorException;
import sa.com.cloudsolutions.antikythera.exception.GeneratorException;
import sa.com.cloudsolutions.antikythera.finch.Finch;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.parser.Callable;
import sa.com.cloudsolutions.antikythera.parser.ImportWrapper;
import sa.com.cloudsolutions.antikythera.parser.MCEWrapper;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;


/**
 * Expression evaluator engine.
 */
public class Evaluator {
    private static final Logger logger = LoggerFactory.getLogger(Evaluator.class);
    /**
     * The fields that were encountered in the current class.
     */
    protected final Map<String, Variable> fields;
    /**
     * <p>Local variables.</p>
     *
     * <p>These are specific to a block statement. A block statement may also be an
     * entire method. The primary key will be the hashcode of the block statement.</p>
     */
    private final Map<Integer, Map<String, Variable>> locals;
    /**
     * The fully qualified name of the class for which we created this evaluator.
     */
    protected String className;

    /**
     * The compilation unit that is being processed by the expression engine
     */
    protected CompilationUnit cu;

    /**
     * The most recent return value that was encountered.
     */
    protected Variable returnValue;

    /**
     * The parent block of the last executed return statement.
     */
    protected Node returnFrom;

    protected LinkedList<Boolean> loops = new LinkedList<>();

    protected Deque<TryStmt> catching = new LinkedList<>();

    protected String variableName;

    protected TypeDeclaration<?> typeDeclaration;

    private static long sequence = 0;

    private static Exception lastException;

    protected Evaluator() {
        locals = new HashMap<>();
        fields = new HashMap<>();
    }

    protected Evaluator(EvaluatorFactory.Context context) {
        this();
        this.className = context.getClassName();
        cu = AntikytheraRunTime.getCompilationUnit(className);
        if (cu != null) {
            typeDeclaration = AbstractCompiler.getMatchingType(cu, className).orElseThrow();
        }
        Finch.loadFinches();
    }

    public static Variable evaluateLiteral(Expression expr) throws EvaluatorException {
        return switch (expr) {
            case BooleanLiteralExpr booleanLiteralExpr ->
                    new Variable(AbstractCompiler.convertLiteralToType(booleanLiteralExpr), booleanLiteralExpr.getValue());
            case DoubleLiteralExpr doubleLiteralExpr ->
                    new Variable(AbstractCompiler.convertLiteralToType(doubleLiteralExpr), Double.parseDouble(doubleLiteralExpr.getValue()));
            case IntegerLiteralExpr integerLiteralExpr ->
                    new Variable(AbstractCompiler.convertLiteralToType(integerLiteralExpr), Integer.parseInt(integerLiteralExpr.getValue()));
            case StringLiteralExpr stringLiteralExpr ->
                    new Variable(AbstractCompiler.convertLiteralToType(stringLiteralExpr), stringLiteralExpr.getValue());
            case CharLiteralExpr charLiteralExpr ->
                    new Variable(AbstractCompiler.convertLiteralToType(charLiteralExpr), charLiteralExpr.getValue());
            case LongLiteralExpr longLiteralExpr -> {
                String value = longLiteralExpr.getValue();
                yield new Variable(Long.parseLong(value.endsWith("L") ? value.replaceFirst("L", "") : value));
            }
            case NullLiteralExpr nullLiteralExpr -> new Variable(null);
            default -> throw new EvaluatorException("Unknown literal expression %s".formatted(expr));
        };
    }

    private static void anonymousOverrides(TypeDeclaration<?> type, ObjectCreationExpr oce) {

        Optional<NodeList<BodyDeclaration<?>>> anonymousClassBody = oce.getAnonymousClassBody();
        /*
         * Merge the anon class stuff into the parent
         */
        anonymousClassBody.ifPresent(bodyDeclarations -> injectMethods(type, bodyDeclarations));
    }

    private static void injectMethods(TypeDeclaration<?> match, NodeList<BodyDeclaration<?>> anonymousClassBody) {
        for (BodyDeclaration<?> body : anonymousClassBody) {
            if (body.isMethodDeclaration()) {
                MethodDeclaration md = body.asMethodDeclaration();
                MethodDeclaration replace = null;

                for (MethodDeclaration method : match.findAll(MethodDeclaration.class)) {
                    if (method.getNameAsString().equals(md.getNameAsString())) {
                        replace = method;
                        break;
                    }
                }
                if (replace != null) {
                    replace.replace(md);
                } else {
                    match.addMember(md);
                }
            }
        }
    }

    protected static Variable executeLambda(Variable e) {
        if (e.getValue() instanceof SupplierEvaluator<?> supplier) {
            Object result = supplier.get();
            if (result instanceof RuntimeException exception) {
                throw exception;
            }
            return new Variable(result);
        } else if (e.getValue() instanceof FunctionEvaluator<?, ?> function) {
            return new Variable(function.apply(null));
        }
        return null;
    }

    static void validateReflectiveMethod(Variable v, ReflectionArguments reflectionArguments, Method method) {
        if (method == null) {
            if (v.getValue() == null) {
                throw new EvaluatorException("Application NPE: " + reflectionArguments.getMethodName(), EvaluatorException.NPE);
            }
            throw new EvaluatorException("Error evaluating method call: " + reflectionArguments.getMethodName());
        }
    }

    static Method getMethod(Callable callable) {
        return callable.getMethod();
    }

    /**
     * <p>Get the value for the given variable in the current scope.</p>
     * <p>
     * The variable may have been defined as a local (which could be a variable defined in the current block
     * or an argument to the function, or in the enclosing block) or a field.
     *
     * @param n    a node depicting the current statement. It will be used to identify the current block
     * @param name the name of the variable.
     * @return the value for the variable in the current scope
     */
    public Variable getValue(Node n, String name) {
        Variable value = getLocal(n, name);
        if (value == null && getField(name) != null) {
            return getField(name);
        }
        return value;
    }

    /**
     * <p>Evaluate an expression.</p>
     * <p>
     * This is a recursive process, evaluating a particular expression might result in this method being called
     * again and again either directly or indirectly.
     *
     * @param expr the expression to evaluate
     * @return the result as a Variable instance which can be null if the expression is supposed to return null
     */
    @SuppressWarnings("java:S3776")
    public Variable evaluateExpression(Expression expr) throws ReflectiveOperationException {
        if (expr.isNameExpr()) {
            String name = expr.asNameExpr().getNameAsString();
            return getValue(expr, name);
        } else if (expr.isMethodCallExpr()) {

            /*
             * Method calls are the toughest nuts to crack. Some method calls will be from the Java api
             * or from other libraries. Or from classes that have not been compiled.
             */
            MethodCallExpr methodCall = expr.asMethodCallExpr();
            return evaluateMethodCall(methodCall);

        } else if (expr.isLiteralExpr()) {
            /*
             * Literal expressions are the easiest.
             */
            return evaluateLiteral(expr);

        } else if (expr.isVariableDeclarationExpr()) {
            /*
             * Variable declarations are hard and deserve their own method.
             */
            return evaluateVariableDeclaration(expr);
        } else if (expr.isBinaryExpr()) {
            /*
             * Binary expressions can also be difficult
             */
            return evaluateBinaryExpression(expr.asBinaryExpr());
        } else if (expr.isUnaryExpr()) {
            return evaluateUnaryExpression(expr);
        } else if (expr.isAssignExpr()) {
            return evaluateAssignment(expr);
        } else if (expr.isObjectCreationExpr()) {
            return createObject(expr.asObjectCreationExpr());
        } else if (expr.isFieldAccessExpr()) {
            return evaluateFieldAccessExpression(expr);
        } else if (expr.isArrayInitializerExpr()) {
            /*
             * Array Initialization is tricky
             */
            ArrayInitializerExpr arrayInitializerExpr = expr.asArrayInitializerExpr();
            return createArray(arrayInitializerExpr);

        } else if (expr.isEnclosedExpr()) {
            /*
             * Enclosed expressions are just brackets around stuff.
             */
            return evaluateExpression(expr.asEnclosedExpr().getInner());
        } else if (expr.isCastExpr()) {
            return evaluateExpression(expr.asCastExpr().getExpression());
        } else if (expr.isConditionalExpr()) {
            return evaluateConditionalExpression(expr.asConditionalExpr());
        } else if (expr.isClassExpr()) {
            return evaluateClassExpression(expr.asClassExpr());
        } else if (expr.isLambdaExpr()) {
            return FPEvaluator.create(expr.asLambdaExpr(), this);
        } else if (expr.isArrayAccessExpr()) {
            return evaluateArrayAccess(expr);
        } else if (expr.isMethodReferenceExpr()) {
            return evaluateExpression(
                    FunctionalConverter.convertToLambda(expr.asMethodReferenceExpr(), new Variable(this)
                    ));
        } else if (expr.isInstanceOfExpr()) {
            return evaluateInstanceOf(expr.asInstanceOfExpr());
        } else if (expr.isThisExpr()) {
            return new Variable(this);
        } else {
            logger.warn("Unsupported expression: {}", expr.getClass().getSimpleName());
        }
        return null;
    }

    private Variable evaluateInstanceOf(InstanceOfExpr expr) throws ReflectiveOperationException {
        Expression left = expr.getExpression();
        Variable l = evaluateExpression(left);
        Type t = expr.getType();
        TypeWrapper wrapper = AbstractCompiler.findType(cu, t);
        if (wrapper != null) {
            boolean assignable;
            if (wrapper.getClazz() != null) {
                assignable = wrapper.getClazz().isAssignableFrom(l.getClazz());
            }
            else {
                Set<String> subs = AntikytheraRunTime.findSubClasses(wrapper.getFullyQualifiedName());
                assignable = subs.contains(l.getClazz().getName());
            }
            expr.getPattern().ifPresent(pattern -> {
                if (pattern.isTypePatternExpr()) {
                    setLocal(expr, pattern.asTypePatternExpr().getNameAsString(), l);
                }
            });
            return new Variable(assignable);
        }
        return new Variable(false);
    }

    private Variable evaluateArrayAccess(Expression expr) throws ReflectiveOperationException {
        ArrayAccessExpr arrayAccessExpr = expr.asArrayAccessExpr();
        Variable array = evaluateExpression(arrayAccessExpr.getName());
        Expression index = arrayAccessExpr.getIndex();
        Variable pos = evaluateExpression(index);
        if (array != null && array.getValue() != null) {
            Object value = Array.get(array.getValue(), (Integer) pos.getValue());
            return new Variable(value);
        }
        return null;
    }

    Variable evaluateClassExpression(ClassExpr classExpr) throws ClassNotFoundException {
        TypeWrapper wrapper = AbstractCompiler.findType(cu, classExpr.getType().asString());

        if (wrapper != null) {
            return evaluateClassExpression(wrapper);
        }
        return null;
    }

    protected Variable evaluateClassExpression(TypeWrapper wrapper) throws ClassNotFoundException {
        if (wrapper.getClazz() != null) {
            return new Variable(wrapper.getClazz());
        }
        if (wrapper.getType() != null) {
            Evaluator evaluator = EvaluatorFactory.createLazily(wrapper.getFullyQualifiedName(), Evaluator.class);
            Class<?> dynamicClass = AKBuddy.createDynamicClass(new MethodInterceptor(evaluator));

            Variable v = new Variable(dynamicClass);
            v.setClazz(Class.class);
            return v;
        }
        return null;
    }

    private Variable evaluateBinaryExpression(BinaryExpr binaryExpr) throws ReflectiveOperationException {
        Expression left = binaryExpr.getLeft();
        Expression right = binaryExpr.getRight();

        return evaluateBinaryExpression(binaryExpr.getOperator(), left, right);
    }

    private Variable evaluateConditionalExpression(ConditionalExpr conditionalExpr) throws ReflectiveOperationException {
        Variable v = evaluateExpression(conditionalExpr.getCondition());
        if (v != null && v.getValue().equals(Boolean.TRUE)) {
            return evaluateExpression(conditionalExpr.getThenExpr());
        } else {
            return evaluateExpression(conditionalExpr.getElseExpr());
        }
    }

    /**
     * Create an array using reflection
     *
     * @param arrayInitializerExpr the ArrayInitializerExpr which describes how the array will be build
     * @return a Variable which holds the generated array as a value
     * @throws ReflectiveOperationException when a reflection method fails
     * @throws AntikytheraException         when a parser operation fails
     */
    Variable createArray(ArrayInitializerExpr arrayInitializerExpr) throws ReflectiveOperationException, AntikytheraException {
        Optional<Node> parent = arrayInitializerExpr.getParentNode();
        if (parent.isPresent() && parent.get() instanceof VariableDeclarator) {

            List<Expression> values = arrayInitializerExpr.getValues();
            Object array = Array.newInstance(Object.class, values.size());

            for (int i = 0; i < values.size(); i++) {
                Object value = evaluateExpression(values.get(i)).getValue();
                if (value instanceof Evaluator evaluator) {
                    MethodInterceptor interceptor = new MethodInterceptor(evaluator);
                    Class<?> dynamicClass = AKBuddy.createDynamicClass(interceptor);
                    Array.set(array, i, AKBuddy.createInstance(dynamicClass, interceptor));
                }
                else {
                    Array.set(array, i, value);
                }
            }

            return new Variable(array);
        }

        return null;
    }

    private Variable evaluateUnaryExpression(Expression expr) throws ReflectiveOperationException {
        UnaryExpr unaryExpr = expr.asUnaryExpr();
        Expression expression = unaryExpr.getExpression();
        Variable v = evaluateExpression(expression);

        switch (unaryExpr.getOperator()) {
            case LOGICAL_COMPLEMENT -> {
                v.setValue(!(Boolean) v.getValue());
                return v;
            }
            case POSTFIX_INCREMENT, PREFIX_INCREMENT -> {
                v.setValue(switch (v.getValue()) {
                    case Integer n -> n + 1;
                    case Double d -> d + 1;
                    case Long l -> l + 1;
                    default -> v.getValue();
                });
                return v;
            }
            case POSTFIX_DECREMENT, PREFIX_DECREMENT -> {
                v.setValue(switch (v.getValue()) {
                    case Integer n -> n - 1;
                    case Double d -> d - 1;
                    case Long l -> l - 1;
                    default -> v.getValue();
                });
                return v;
            }
            case MINUS -> {
                v.setValue(switch (v.getValue()) {
                    case Integer n -> -n;
                    case Double d -> -d;
                    case Long l -> -l;
                    default -> v.getValue();
                });
                return v;
            }
            default -> {
                logger.warn("Unsupported unary operation: {}", unaryExpr.getOperator());
                return null;
            }
        }
    }

    @SuppressWarnings("java:S3011")
    Variable evaluateFieldAccessExpression(Expression expr) throws ReflectiveOperationException {
        FieldAccessExpr fae = expr.asFieldAccessExpr();

        String fullName = AbstractCompiler.findFullyQualifiedName(cu, fae.getScope().toString());
        if (fullName != null) {
            CompilationUnit dep = AntikytheraRunTime.getCompilationUnit(fullName);
            if (dep == null) {
                /*
                 * Use class loader
                 */
                Class<?> clazz = AbstractCompiler.loadClass(fullName);
                Field field = clazz.getDeclaredField(fae.getNameAsString());
                field.setAccessible(true);
                return new Variable(new ClassOrInterfaceType().setName(field.getType().getName()), field.get(null));
            } else {
                Variable v = evaluateFieldAccessExpression(fae, dep);
                if (v != null) {
                    return v;
                }
            }
        }
        Variable v = evaluateExpression(fae.getScope());
        if (v != null) {
            if (v.getValue() instanceof Evaluator eval) {
                return eval.getField(fae.getNameAsString());
            } else if (v.getValue() != null && v.getValue().getClass().isArray()) {
                if (fae.getNameAsString().equals("length")) {
                    return new Variable(Array.getLength(v.getValue()));
                }
                logger.warn("Array field access {} not supported", fae.getNameAsString());
            }
        }
        logger.warn("Could not resolve {} for field access", fae.getScope());


        return null;
    }

    private Variable evaluateFieldAccessExpression(FieldAccessExpr fae, CompilationUnit dep) {
        Optional<TypeDeclaration<?>> td = AbstractCompiler.getMatchingType(dep, fae.getScope().toString());
        if (td.isEmpty()) {
            return null;
        }
        Optional<FieldDeclaration> fieldDeclaration = td.get().getFieldByName(fae.getNameAsString());
        if (fieldDeclaration.isPresent()) {
            FieldDeclaration field = fieldDeclaration.get();
            for (var variable : field.getVariables()) {
                if (variable.getNameAsString().equals(fae.getNameAsString())) {
                    if (field.isStatic()) {
                        return AntikytheraRunTime.getStaticVariable(
                                getClassName() + "." + fae.getScope().toString(), variable.getNameAsString());
                    }
                    Variable v = new Variable(field.getVariable(0).getType().asString());
                    variable.getInitializer().ifPresent(f -> v.setValue(f.toString()));
                    return v;
                }
            }
        }
        else if (td.get().isEnumDeclaration()) {
            EnumDeclaration enumDeclaration = td.get().asEnumDeclaration();
            return AntikytheraRunTime.getStaticVariable(enumDeclaration.getFullyQualifiedName().orElseThrow(), fae.getNameAsString());
        }
        return null;
    }

    @SuppressWarnings("java:S3011")
    private Variable evaluateAssignment(Expression expr) throws ReflectiveOperationException {
        AssignExpr assignExpr = expr.asAssignExpr();
        Expression target = assignExpr.getTarget();
        Expression value = assignExpr.getValue();

        Variable v = switch (assignExpr.getOperator()) {
            case PLUS -> evaluateBinaryExpression(BinaryExpr.Operator.PLUS, target, value);
            case MULTIPLY -> evaluateBinaryExpression(BinaryExpr.Operator.MULTIPLY, target, value);
            case MINUS -> evaluateBinaryExpression(BinaryExpr.Operator.MINUS, target, value);
            case DIVIDE -> evaluateBinaryExpression(BinaryExpr.Operator.DIVIDE, target, value);
            default -> evaluateExpression(value);
        };

        if (target.isFieldAccessExpr()) {
            FieldAccessExpr fae = target.asFieldAccessExpr();
            String fieldName = fae.getNameAsString();
            Variable variable = fae.getScope().toString().equals("this")
                    ? getValue(expr, fieldName)
                    : getValue(expr, fae.getScope().toString());

            Object obj = variable.getValue();
            if (obj instanceof Evaluator eval) {
                eval.setField(fae.getNameAsString(), v);
            } else {
                try {
                    Field field = obj.getClass().getDeclaredField(fieldName);
                    field.setAccessible(true);
                    field.set(obj, v.getValue());
                } catch (ReflectiveOperationException | NullPointerException e) {
                    /*
                     * This is not something that was created with class.forName or byte buddy.
                     */
                    this.fields.put(fieldName, v);
                }
            }
        } else if (target.isNameExpr()) {
            String name = target.asNameExpr().getNameAsString();
            setLocal(expr, name, v);
        }

        return v;
    }

    public void setField(String nameAsString, Variable v) {
        fields.put(nameAsString, v);
    }

    /**
     * Evaluates a variable declaration expression.
     * Will return the result of the variable declaration as well as saving it in the symbol table.
     *
     * @param expr the expression
     * @return a Variable or null if the expression could not be evaluated.
     * If the expression itself evaluates to null, the result will be a Variable instance
     * holding a null value.
     * <p>
     * in some cases multiple variables can be declared in a single line. In such a situation
     * the returned value will be the last variable declared. you will need to fetch the rest
     * using the local symbol table.
     * @throws ReflectiveOperationException if a reflective operation failed
     */
    @SuppressWarnings("java:S2259")
    Variable evaluateVariableDeclaration(Expression expr) throws ReflectiveOperationException {
        VariableDeclarationExpr varDeclExpr = expr.asVariableDeclarationExpr();
        Variable v = null;
        for (var decl : varDeclExpr.getVariables()) {
            Optional<Expression> init = decl.getInitializer();
            if (init.isPresent()) {
                v = initializeVariable(decl, init.get());
                /*
                 * this could raise a null pointer exception, that's fine with me. that means
                 * some other code is misbehaving and this NPE will give a change to catch it
                 */
                v.setInitializer(List.of(init.get()));
            } else {
                /*
                 * No initializer. We need to create an entry in the symbol table. If the variable is
                 * primitive, we need to set the appropriate default value. Non-primitives will be null
                 */
                if (decl.getType().isPrimitiveType()) {
                    Object obj = Reflect.getDefault(decl.getType().asString());
                    v = new Variable(decl.getType(), obj);

                } else {
                    v = new Variable(decl.getType(), null);

                }
                setLocal(expr, decl.getNameAsString(), v);
            }
        }
        return v;
    }

    private Variable initializeVariable(VariableDeclarator decl, Expression init) throws ReflectiveOperationException {
        Variable v = evaluateExpression(init);

        if (v != null) {
            v.setType(decl.getType());
            setLocal(decl, decl.getNameAsString(), v);
        }

        return v;
    }

    /**
     * Create an object using reflection or an evaluator
     * @param oce                The expression to be evaluated and assigned as the initial value
     * @return The object that's created will be in the value field of the Variable
     */
    Variable createObject(ObjectCreationExpr oce) throws ReflectiveOperationException {
        ClassOrInterfaceType type = oce.getType();
        TypeWrapper wrapper = AbstractCompiler.findType(cu, type.getNameAsString());
        if (wrapper == null) {
            return null;
        }

        return createObject(oce, wrapper);
    }

    protected Variable createObject(ObjectCreationExpr oce, TypeWrapper wrapper) throws ReflectiveOperationException {
        if (wrapper.getType() != null) {
            return createUsingEvaluator(wrapper.getType(), oce);
        }
        if (wrapper.getClazz() != null) {
            return createUsingReflection(wrapper.getClazz(), oce);
        }
        return null;
    }

    /**
     * Create a new object as an evaluator instance.
     *
     * @param oce the object creation expression.
     */
    private Variable createUsingEvaluator(TypeDeclaration<?> match, ObjectCreationExpr oce) throws ReflectiveOperationException {
        if (match != null) {
            /* needs eager creation */
            Evaluator eval = EvaluatorFactory.create(match.getFullyQualifiedName().orElseThrow(), this);
            anonymousOverrides(match, oce);
            return createUsingEvaluator(match, oce, eval);
        }

        return null;
    }

    private Variable createUsingEvaluator(TypeDeclaration<?> match, ObjectCreationExpr oce, Evaluator eval) throws ReflectiveOperationException {
        List<ConstructorDeclaration> constructors = match.findAll(ConstructorDeclaration.class);
        if (constructors.isEmpty()) {
            return new Variable(eval);
        }
        MCEWrapper mce = wrapCallExpression(oce);

        Optional<Callable> matchingConstructor = AbstractCompiler.findConstructorDeclaration(mce, match);

        if (matchingConstructor.isPresent()) {
            eval.executeConstructor(matchingConstructor.get().getCallableDeclaration());
            return new Variable(eval);
        }
        /*
         * No matching constructor found but in evals the default does not show up. So let's roll
         */
        return new Variable(eval);
    }

    protected MCEWrapper wrapCallExpression(NodeWithArguments<?> oce) throws ReflectiveOperationException {
        MCEWrapper mce = new MCEWrapper(oce);
        NodeList<Type> argTypes = new NodeList<>();
        Deque<Type> args = new LinkedList<>();
        mce.setArgumentTypes(argTypes);

        for (int i = oce.getArguments().size() - 1; i >= 0; i--) {
            /*
             * Push method arguments
             */
            Expression expr = oce.getArguments().get(i);
            if (expr.isLambdaExpr()) {
                Variable variable = FPEvaluator.create(expr.asLambdaExpr(), this);
                args.push(variable.getType());
                AntikytheraRunTime.push(variable);
            } else {
                Variable variable = evaluateExpression(expr);
                if (variable.getType() == null && variable.getValue() instanceof Evaluator eval) {
                    variable.setType(AbstractCompiler.typeFromDeclaration(
                            AntikytheraRunTime.getTypeDeclaration(eval.getClassName()).orElseThrow()));
                }
                args.push(variable.getType());
                AntikytheraRunTime.push(variable);
            }
        }

        while (!args.isEmpty()) {
            argTypes.add(args.pop());
        }

        return mce;
    }

    /**
     * Create a new object using reflection.
     * Typically intended for use for classes contained in the standard library.
     *
     * @param oce the object creation expression
     * @return a Variable if the instance could be created or null.
     */
    private Variable createUsingReflection(Class<?> clazz, ObjectCreationExpr oce) {
        try {
            ReflectionArguments reflectionArguments = Reflect.buildArguments(oce, this, null);

            Constructor<?> cons = Reflect.findConstructor(clazz, reflectionArguments.getArgumentTypes(),
                    reflectionArguments.getArguments());
            if (cons != null) {
                Object instance = cons.newInstance(reflectionArguments.getArguments());
                Variable v = new Variable(instance);
                v.setClazz(clazz);
                return v;
            } else {
                throw new EvaluatorException("Could not find a constructor for class " + clazz.getName());
            }

        } catch (Exception e) {
            logger.warn("Could not create an instance of type {} using reflection", clazz);
            logger.warn("The error was {}", e.getMessage());

        }
        return null;
    }

    /**
     * Find local variable
     * Does not look at fields. You probably want to call getValue() instead.
     *
     * @param node the node representing the current expression.
     *             Its primary purpose is to help identify the current block
     * @param name the name of the variable to look up
     * @return the Variable if it's found or null.
     */
    @SuppressWarnings("java:S3776")
    public Variable getLocal(Node node, String name) {
        Node n = node;

        while (n != null) {
            BlockStmt block = AbstractCompiler.findBlockStatement(n);
            int hash = (block != null) ? block.hashCode() : 0;
            if (hash == 0) {
                for (Map<String, Variable> entry : locals.values()) {
                    Variable v = entry.get(name);
                    if (v != null) {
                        return v;
                    }
                }
                break;
            }

            Map<String, Variable> localsVars = this.locals.get(hash);

            if (localsVars != null) {
                Variable v = localsVars.get(name);
                if (v != null)
                    return v;
            }
            if (n instanceof MethodDeclaration) {
                localsVars = this.locals.get(hash);
                return localsVars == null ? null : localsVars.get(name);
            }

            n = block.getParentNode().orElse(null);

        }
        return null;
    }

    /**
     * Sets a local variable
     *
     * @param node         An expression representing the code being currently executed. It will be used to identify the
     *                     encapsulating block.
     * @param nameAsString the variable name.
     *                     If the variable is already available as a local it's value will be replaced.
     * @param v            The value to be set for the variable.
     */
    void setLocal(Node node, String nameAsString, Variable v) {
        Optional<Node> parent = node.getParentNode();
        if (parent.isEmpty() ||  !(parent.get() instanceof VariableDeclarationExpr)) {
            Variable old = getValue(node, nameAsString);
            if (old != null) {
                old.setValue(v.getValue());
                return;
            }
        }

        BlockStmt block = AbstractCompiler.findBlockStatement(node);
        int hash = (block != null) ? block.hashCode() : 0;

        Map<String, Variable> localVars = this.locals.computeIfAbsent(hash, k -> new HashMap<>());
        localVars.put(nameAsString, v);
    }

    /**
     * <p>Evaluate a method call.</p>
     *
     * <p>There are two types of method calls, those that return values and those that do not.
     * The ones that return values will typically reach here through a flow such as initialize
     * variables. Void method calls will typically reach this method through the evaluate
     * expression flow.</p>
     * <p>
     * Evaluates a method call by finding the method declaration and executing all the code
     * contained in that method where possible.
     *
     * @param methodCall the method call expression
     * @return the result of executing that code.
     * @throws EvaluatorException if there is an error evaluating the method call, or if the
     *                            feature is not yet implemented.
     */
    public Variable evaluateMethodCall(MethodCallExpr methodCall) throws ReflectiveOperationException {
        ScopeChain chain = ScopeChain.findScopeChain(methodCall);

        if (chain.isEmpty()) {
            MCEWrapper wrapper = wrapCallExpression(methodCall);
            return executeLocalMethod(wrapper);
        }

        return evaluateScopedMethodCall(chain);
    }

    Variable evaluateScopedMethodCall(ScopeChain chain) throws ReflectiveOperationException {
        MethodCallExpr methodCall = chain.getExpression().asMethodCallExpr();

        Variable variable = evaluateScopeChain(chain);
        if (variable.getValue() instanceof Optional<?> optional && optional.isEmpty()) {
            Variable o = handleOptionalEmpties(chain);
            if (o != null) {
                return o;
            }
        }
        Scope scope = chain.getChain().getLast();
        scope.setScopedMethodCall(methodCall);
        scope.setVariable(variable);
        return evaluateMethodCall(scope);
    }

    Variable handleOptionals(Scope scope) throws ReflectiveOperationException {
        Callable callable = scope.getMCEWrapper().getMatchingCallable();
        if (callable.isCallableDeclaration()) {
            MethodDeclaration md = callable.asMethodDeclaration();
            Variable v = executeMethod(md);
            if (v != null && v.getValue() == null) {
                v.setType(md.getType());
            }
            return v;
        } else {
            Method m = callable.getMethod();
            return executeMethod(m);
        }
    }

    Variable handleOptionalEmpties(ScopeChain chain) throws ReflectiveOperationException {
        MethodCallExpr methodCall = chain.getExpression().asMethodCallExpr();
        String methodName = methodCall.getNameAsString();

        if (methodName.equals("orElseThrow")) {
            Optional<Expression> args = methodCall.getArguments().getFirst();

            if (args.isEmpty()) {
                /*
                 * Simulation of throwing a no such element exception when the optional
                 * is empty.
                 */
                throw new NoSuchElementException();
            }
            Expression expr = args.get();
            if (expr.isMethodCallExpr()) {
                return evaluateMethodCall(expr.asMethodCallExpr());
            } else if (expr.isLambdaExpr()) {
                Variable e = FPEvaluator.create(expr.asLambdaExpr(), this);
                return executeLambda(e);
            }
        }

        return null;
    }

    public Variable evaluateScopeChain(ScopeChain chain) throws ReflectiveOperationException {
        Variable variable = null;
        for (Scope scope : chain.getChain().reversed()) {
            Expression expr2 = scope.getExpression();
            if (expr2.isNameExpr()) {
                variable = resolveExpression(expr2.asNameExpr());
            } else if (expr2.isFieldAccessExpr()) {
                if (variable != null) {
                    /*
                     * getValue should have returned to us a valid field. That means
                     * we will have an evaluator instance as the 'value' in the variable v
                     */
                    variable = evaluateScopedFieldAccess(variable, expr2);
                }
                else if (scope.getTypeWrapper() != null){
                    if (scope.getTypeWrapper().getClazz() != null) {
                        variable = new Variable(scope.getTypeWrapper().getClazz());
                        variable.setClazz(scope.getTypeWrapper().getClazz());
                    }
                    else {
                        variable = new Variable(EvaluatorFactory.create(scope.getTypeWrapper().getFullyQualifiedName(), this));
                    }
                    break;
                }
            } else if (expr2.isMethodCallExpr()) {
                scope.setVariable(variable);
                scope.setScopedMethodCall(expr2.asMethodCallExpr());
                variable = evaluateMethodCall(scope);
            } else if (expr2.isLiteralExpr()) {
                variable = evaluateLiteral(expr2);
            } else if (expr2.isThisExpr()) {
                variable = new Variable(this);
            } else if (expr2.isTypeExpr()) {
                String s = expr2.toString();
                Object scopeType = findScopeType(s);
                variable = new Variable(scopeType);
                if (scopeType instanceof Class<?> clazz) {
                    variable.setClazz(clazz);
                }
            } else if (expr2.isObjectCreationExpr()) {
                variable = createObject(expr2.asObjectCreationExpr());
            } else if (expr2.isArrayAccessExpr()) {
                variable = evaluateArrayAccess(expr2);
            }
        }
        return variable;
    }

    private Variable evaluateScopedFieldAccess(Variable variable, Expression expr2) throws ReflectiveOperationException {
        if (variable.getClazz() != null && variable.getClazz().equals(System.class)) {
            Field field = System.class.getField(expr2.asFieldAccessExpr().getNameAsString());
            variable = new Variable(field.get(null));
        } else if (variable.getValue() instanceof Evaluator eval) {
            variable = eval.getValue(expr2, expr2.asFieldAccessExpr().getNameAsString());
        } else {
            if (variable.getValue() instanceof Evaluator eval) {
                variable = eval.evaluateFieldAccessExpression(expr2.asFieldAccessExpr());
            } else {
                variable = evaluateFieldAccessExpression(expr2.asFieldAccessExpr());
            }
        }
        return variable;
    }

    @SuppressWarnings("java:S106")
    protected Object findScopeType(String s) {
        return switch (s) {
            case "System.out" -> System.out;
            case "System.err" -> System.err;
            case "System.in" -> System.in;
            default -> {
                String fullyQualifiedName = AbstractCompiler.findFullyQualifiedName(cu, s);
                CompilationUnit targetCu = AntikytheraRunTime.getCompilationUnit(fullyQualifiedName);
                if (targetCu != null) {
                    Optional<TypeDeclaration<?>> typeDecl = AbstractCompiler.getMatchingType(targetCu, s);
                    if (typeDecl.isPresent()) {
                        /* eagerly create an evaluator */
                        yield EvaluatorFactory.create(typeDecl.get().getFullyQualifiedName().orElse(null), this);
                    }
                }
                yield null;
            }
        };
    }

    protected Variable resolveExpression(NameExpr expr) {
        if (expr.getNameAsString().equals("System")) {
            Variable variable = new Variable(System.class);
            variable.setClazz(System.class);
            return variable;
        } else {
            Variable v = getValue(expr, expr.asNameExpr().getNameAsString());
            if (v == null) {
                /*
                 * We know that we don't have a matching local variable or field. That indicates the
                 * presence of an import, a class from same package or this is part of java.lang package
                 * or a Static import
                 */
                TypeWrapper wrapper = AbstractCompiler.findType(cu, expr.getNameAsString());
                if (wrapper != null) {
                    Class<?> clazz = wrapper.getClazz();
                    if (clazz != null) {
                        v = new Variable(clazz);
                        v.setClazz(clazz);
                    } else {
                        v = resolveExpressionHelper(wrapper);
                    }
                }
            }

            return v;
        }
    }

    protected Variable resolveExpressionHelper(TypeWrapper wrapper) {
        if (wrapper.getType() != null) {
            Variable v;
            Evaluator eval = EvaluatorFactory.create(wrapper.getType().getFullyQualifiedName().orElseThrow(), this);
            v = new Variable(eval);
            return v;
        }
        return null;
    }

    public Variable evaluateMethodCall(Scope scope) throws ReflectiveOperationException {
        Variable v = scope.getVariable();
        MethodCallExpr methodCall = scope.getScopedMethodCall();
        if (v != null) {
            Object value = v.getValue();
            if (value instanceof Evaluator eval && eval.getCompilationUnit() != null) {
                MCEWrapper wrapper = wrapCallExpression(methodCall);
                scope.setMCEWrapper(wrapper);
                return eval.executeMethod(scope);
            }

            ReflectionArguments reflectionArguments = Reflect.buildArguments(methodCall, this, v);
            return reflectiveMethodCall(v, reflectionArguments);
        } else {
            MCEWrapper wrapper = wrapCallExpression(methodCall);
            scope.setMCEWrapper(wrapper);
            return executeMethod(scope);
        }
    }

    Variable reflectiveMethodCall(Variable v, ReflectionArguments reflectionArguments) throws ReflectiveOperationException {
        Method method = Reflect.findAccessibleMethod(v.getClazz(), reflectionArguments);
        validateReflectiveMethod(v, reflectionArguments, method);
        reflectionArguments.setMethod(method);
        reflectionArguments.finalizeArguments();
        invokeReflectively(v, reflectionArguments);
        return returnValue;
    }

    void invokeReflectively(Variable v, ReflectionArguments reflectionArguments) throws ReflectiveOperationException {
        Method method = reflectionArguments.getMethod();

        Object[] finalArgs = reflectionArguments.getFinalArgs();
        try {
            returnValue = new Variable(method.invoke(v.getValue(), finalArgs));
            if (returnValue.getValue() == null && returnValue.getClazz() == null) {
                returnValue.setClazz(method.getReturnType());
            }
        } catch (IllegalAccessException e) {
            invokeinAccessibleMethod(v, reflectionArguments);
        }
    }

    @SuppressWarnings("java:S3011")
    private void invokeinAccessibleMethod(Variable v, ReflectionArguments reflectionArguments) throws ReflectiveOperationException {
        Method method = reflectionArguments.getMethod();
        Object[] finalArgs = reflectionArguments.getFinalArgs();
        try {
            method.setAccessible(true);

            returnValue = new Variable(method.invoke(v.getValue(), finalArgs));
            if (returnValue.getValue() == null && returnValue.getClazz() == null) {
                returnValue.setClazz(method.getReturnType());
            }
        } catch (InaccessibleObjectException ioe) {
            // If module access fails, try to find a public interface or superclass method
            Method publicMethod = Reflect.findPublicMethod(v.getClazz(), reflectionArguments.getMethodName(), reflectionArguments.getArgumentTypes());
            if (publicMethod != null) {
                returnValue = new Variable(publicMethod.invoke(v.getValue(), finalArgs));
                if (returnValue.getValue() == null && returnValue.getClazz() == null) {
                    returnValue.setClazz(publicMethod.getReturnType());
                }
            }
        }
    }

    /**
     * Execute a method that is part of a chain of method calls
     *
     * @param sc the method scope
     * @return the result from executing the method or null if the method is void.
     * @throws ReflectiveOperationException if the execution involves a class available only
     *                                      in byte code format and an exception occurs in reflecting.
     */
    public Variable executeMethod(Scope sc) throws ReflectiveOperationException {
        returnFrom = null;
        TypeDeclaration<?> cdecl = AbstractCompiler.getMatchingType(cu, getClassName()).orElseThrow();
        MCEWrapper mceWrapper = sc.getMCEWrapper();
        Optional<Callable> n = AbstractCompiler.findCallableDeclaration(mceWrapper, cdecl);

        if (n.isPresent()) {
            mceWrapper.setMatchingCallable(n.get());
            return executeCallable(sc, n.get());
        }
        return executeGettersOrSetters(mceWrapper, cdecl);
    }

    private Variable executeGettersOrSetters(MCEWrapper mceWrapper, TypeDeclaration<?> classOrInterfaceDeclaration) {
        return handleLombokAccessors(classOrInterfaceDeclaration, mceWrapper.getMethodName());
    }

    Variable executeViaDataAnnotation(ClassOrInterfaceDeclaration c, MethodCallExpr methodCall) {
        return handleLombokAccessors(c, methodCall.getNameAsString());
    }

    protected Variable executeCallable(Scope sc, Callable callable) throws ReflectiveOperationException {
        if (callable.isMethodDeclaration()) {
            MethodDeclaration methodDeclaration = callable.asMethodDeclaration();
            Type returnType = methodDeclaration.getType();

            if (returnType.asString().startsWith("Optional") ||
                    returnType.asString().startsWith(Reflect.JAVA_UTIL_OPTIONAL)) {
                return handleOptionals(sc);
            } else {
                Variable v = executeMethod(methodDeclaration);
                if (v != null && v.getValue() == null) {

                    v.setType(returnType);
                }
                return v;
            }
        } else {
            Method method = getMethod(callable);
            Class<?> clazz = method.getReturnType();
            if (Optional.class.equals(clazz)) {
                return handleOptionals(sc);
            }
            Variable variable = sc.getVariable();
            MethodCallExpr mce = sc.getMCEWrapper().asMethodCallExpr().orElseThrow();
            if (variable.getValue() instanceof Evaluator eval) {
                MethodInterceptor interceptor = new MethodInterceptor(eval);
                Class<?> c = AKBuddy.createDynamicClass(interceptor);
                Object instance = AKBuddy.createInstance(c, interceptor);
                Variable v  = new Variable(instance);
                ReflectionArguments reflectionArguments = Reflect.buildArguments(mce, this, v);
                return reflectiveMethodCall(v, reflectionArguments);
            }
            else {
                ReflectionArguments reflectionArguments = Reflect.buildArguments(mce, this, variable);
                return reflectiveMethodCall(variable, reflectionArguments);
            }
        }
    }

    @SuppressWarnings("java:S1172")
    Variable executeMethod(Method m) {
        logger.error("NOt implemented yet");
        throw new AntikytheraException("Not yet implemented"); // but see MockingEvaluator
    }

    /**
     * Execute a method that has not been prefixed by a scope.
     * That means the method being called is a member of the current class or a parent of the current class.
     *
     * @param methodCallWrapper the method call expression to be executed
     * @return a Variable containing the result of the method call
     * @throws AntikytheraException         if there are parsing-related errors
     * @throws ReflectiveOperationException if there are reflection-related errors
     */
    @SuppressWarnings("unchecked")
    public Variable executeLocalMethod(MCEWrapper methodCallWrapper) throws ReflectiveOperationException {
        returnFrom = null;
        NodeWithArguments<?> call = methodCallWrapper.getMethodCallExpr();
        if (call instanceof MethodCallExpr methodCall) {

            Optional<TypeDeclaration<?>> cdecl = (Optional<TypeDeclaration<?>>)
                (Optional<?>) methodCall.findAncestor(TypeDeclaration.class);
            if (cdecl.isEmpty()) {
                cdecl = AbstractCompiler.getMatchingType(cu, getClassName());
            }
            if (cdecl.isPresent()) {
                /*
                 * At this point we are searching for the method call in the current class. For example,
                 * it maybe a getter or setter that has been defined through lombok annotations.
                 */

                Optional<Callable> mdecl = AbstractCompiler.findMethodDeclaration(methodCallWrapper, cdecl.get());

                if (mdecl.isPresent()) {
                    return executeMethod(mdecl.get().getCallableDeclaration());
                } else if (cdecl.get().isClassOrInterfaceDeclaration()) {
                    return executeViaDataAnnotation(cdecl.get().asClassOrInterfaceDeclaration(), methodCall);
                }
            }
        }
        return null;
    }

    private Variable handleLombokAccessors(TypeDeclaration<?> classDecl, String methodName) {
        boolean hasData = classDecl.getAnnotationByName("Data").isPresent();
        boolean hasGetter = hasData || classDecl.getAnnotationByName("Getter").isPresent();
        boolean hasSetter = hasData || classDecl.getAnnotationByName("Setter").isPresent();

        if (methodName.startsWith("get") && hasGetter) {
            String field = AbstractCompiler.classToInstanceName(methodName.replace("get", ""));
            return getField(field);
        }

        if (methodName.startsWith("is") && hasGetter) {
            String field = AbstractCompiler.classToInstanceName(methodName.replace("is", ""));
            Variable v = getField(field);
            if (v == null) {
                return getField(methodName);
            }
            return v;
        }

        if (methodName.startsWith("set") && hasSetter) {
            String field = AbstractCompiler.classToInstanceName(methodName.replace("set", ""));
            Variable old = getField(field);
            if (old == null) {
                field = "is" + methodName.replace("set", "");
            }
            Variable va = AntikytheraRunTime.pop();
            fields.put(field, va);
            return new Variable(null);
        }

        return null;
    }

    /**
     * Execute a method that is available only in source code format.
     *
     * @param methodCall method call that is present in source code form
     * @return the result of the method call wrapped in a Variable
     * @throws AntikytheraException         if there are parsing related errors
     * @throws ReflectiveOperationException if there are reflection related errors
     */
    Variable executeSource(MethodCallExpr methodCall) throws ReflectiveOperationException {

        TypeDeclaration<?> decl = AbstractCompiler.getMatchingType(cu,
                ClassProcessor.instanceToClassName(AbstractCompiler.fullyQualifiedToShortName(className))).orElse(null);
        if (decl != null) {
            MCEWrapper wrapper = wrapCallExpression(methodCall);
            Optional<Callable> md = AbstractCompiler.findMethodDeclaration(wrapper, decl);
            if (md.isPresent() && md.get().isMethodDeclaration()) {
                return executeMethod(md.get().asMethodDeclaration());
            }
        }
        return null;
    }

    Variable evaluateBinaryExpression(BinaryExpr.Operator operator,
                                      Expression leftExpression, Expression rightExpression) throws ReflectiveOperationException {
        Variable left = evaluateExpression(leftExpression);
        if (operator.equals(BinaryExpr.Operator.OR) && (boolean) left.getValue()) {
            return new Variable(Boolean.TRUE);
        }
        if (operator.equals(BinaryExpr.Operator.AND) && !((boolean) left.getValue())) {
            return new Variable(Boolean.FALSE);
        }
        Variable right = evaluateExpression(rightExpression);
        return BinaryOps.binaryOps(operator, leftExpression, rightExpression, left, right);
    }

    @SuppressWarnings({"java:S3776", "java:S1130"})
    Variable resolveVariableDeclaration(VariableDeclarator variable) throws ReflectiveOperationException {
        List<TypeWrapper> resolvedTypes = AbstractCompiler.findTypesInVariable(variable);
        String registryKey = MockingRegistry.generateRegistryKey(resolvedTypes);

        if (!registryKey.isEmpty() && MockingRegistry.isMockTarget(registryKey)) {
                return MockingRegistry.mockIt(variable);
        }

        if (variable.getType().isClassOrInterfaceType()) {
            return resolveNonPrimitiveVariable(variable);
        } else {
            return resolvePrimitiveVariable(variable);
        }
    }

    public void setVariableName(String variableName) {
        this.variableName = variableName;
    }


    public Map<Integer, Map<String, Variable>> getLocals() {
        return locals;
    }

    @SuppressWarnings("unchecked")
    protected void invokeDefaultConstructor() {
        String[] parts = className.split("\\.");
        String shortName = parts[parts.length - 1];

        for (ConstructorDeclaration decl : cu.findAll(ConstructorDeclaration.class)) {

            if (decl.getParameters().isEmpty()) {
                decl.findAncestor(TypeDeclaration.class).ifPresent(t -> {
                    if (shortName.equals(t.getNameAsString())) {
                        try {
                            executeConstructor(decl);
                        } catch (ReflectiveOperationException e) {
                            throw new AntikytheraException(e);
                        }
                    }
                });
            }
        }
    }

    Variable resolveNonPrimitiveVariable(VariableDeclarator variable) throws ReflectiveOperationException {
        ClassOrInterfaceType t = variable.getType().asClassOrInterfaceType();
        List<ImportWrapper> imports = AbstractCompiler.findImport(cu, t);
        if (imports.isEmpty()) {
            String fqn = AbstractCompiler.findFullyQualifiedName(cu, t.getNameAsString());
            if (fqn != null) {
                return resolveNonPrimitiveVariable(fqn, variable, t);
            }
            return resolvePrimitiveOrBoxedVariable(variable, t);
        } else {
            for (ImportWrapper imp : imports) {
                String resolvedClass = imp.getNameAsString();
                Variable v = resolveNonPrimitiveVariable(resolvedClass, variable, t);
                if (v != null) {
                    if (v.getType() == null) {
                        v.setType(t);
                    }
                    return v;
                }
            }
        }
        return null;
    }

    Variable resolveNonPrimitiveVariable(String resolvedClass, VariableDeclarator variable, ClassOrInterfaceType t) throws ReflectiveOperationException {
        Object f = Finch.getFinch(resolvedClass);
        if (f != null) {
            Variable v = new Variable(t);
            v.setValue(f);
            return v;
        } else if (resolvedClass != null) {
            CompilationUnit compilationUnit = AntikytheraRunTime.getCompilationUnit(resolvedClass);
            if (compilationUnit != null) {
                return resolveVariableRepresentedByCode(variable, resolvedClass);
            } else {
                return resolvePrimitiveOrBoxedVariable(variable, t);
            }
        }
        return null;
    }

    @SuppressWarnings("java:S3655")
    protected Variable resolvePrimitiveOrBoxedVariable(VariableDeclarator variable, Type t) throws ReflectiveOperationException {
        Variable v;
        Optional<Expression> init = variable.getInitializer();
        if (init.isPresent()) {
            Expression initExpr = init.get();
            if (initExpr instanceof MethodCallExpr mce && mce.getScope().isPresent()
                    && mce.getScope().get() instanceof NameExpr nameExpr && nameExpr.getNameAsString().equals("LoggerFactory")) {
                Class<?> clazz = AKBuddy.createDynamicClass(new MethodInterceptor(this));
                Logger log = new AKLogger(clazz);
                return new Variable(log);
            }

            v = evaluateExpression(initExpr);
            if (v == null && initExpr.isNameExpr()) {
                /*
                 * This path is usually taken when we are trying to initializer a field to have
                 * a value defined in an external constant.
                 */
                NameExpr nameExpr = initExpr.asNameExpr();
                String name = nameExpr.getNameAsString();

                ImportWrapper importWrapper = AbstractCompiler.findImport(cu, name);
                if (importWrapper != null && importWrapper.getImport().isStatic()) {
                    Evaluator eval = EvaluatorFactory.create(
                            importWrapper.getType().getFullyQualifiedName().orElseThrow(), Evaluator.class);
                    v = eval.getField(name);
                }
            }
        } else {
            v = new Variable(t, null);
            v.setType(t);
        }
        return v;
    }

    /**
     * Try to identify the compilation unit that represents the given field
     *
     * @param variable      a variable declaration statement
     * @param resolvedClass the name of the class that the field is of
     * @return The variable or null
     * @throws AntikytheraException         if something goes wrong
     * @throws ReflectiveOperationException if a reflective operation fails
     */
    Variable resolveVariableRepresentedByCode(VariableDeclarator variable, String resolvedClass) throws ReflectiveOperationException {
        Optional<Expression> init = variable.getInitializer();
        if (init.isPresent()) {
            if (init.get().isObjectCreationExpr()) {
                Variable v = createObject(init.get().asObjectCreationExpr());
                v.setType(variable.getType());
                return v;
            } else {
                Evaluator eval = EvaluatorFactory.create(resolvedClass, this);
                Variable v = new Variable(eval);
                v.setType(variable.getType());
                return v;

            }
        }
        return null;
    }

    private Variable resolvePrimitiveVariable(VariableDeclarator variable) throws ReflectiveOperationException {
        Variable v;
        Optional<Expression> init = variable.getInitializer();
        if (init.isPresent()) {
            v = evaluateExpression(init.get());
            v.setType(variable.getType());
        } else {
            v = new Variable(variable.getType(), Reflect.getDefault(variable.getType().toString()));
        }
        return v;
    }

    public Variable getField(String name) {
        Variable v = fields.get(name);
        if (v == null && typeDeclaration != null && typeDeclaration.isEnumDeclaration()) {
            return AntikytheraRunTime.getStaticVariable(typeDeclaration.getFullyQualifiedName().orElseThrow(), name);
        }
        return v;
    }

    public void visit(MethodDeclaration md) throws ReflectiveOperationException {
        executeMethod(md);
    }

    /**
     * Execute a method represented by the CallableDeclaration
     *
     * @param cd a callable declaration
     * @return the result of the method execution. If the method is void, this will be null
     * @throws AntikytheraException         if the method cannot be executed as source
     * @throws ReflectiveOperationException if various reflective operations associated with the
     *                                      method execution fails
     */
    public Variable executeMethod(CallableDeclaration<?> cd) throws ReflectiveOperationException {
        if (cd instanceof MethodDeclaration md) {

            returnFrom = null;
            returnValue = null;

            List<Statement> statements = md.getBody().orElseThrow().getStatements();
            setupParameters(md);

            executeBlock(statements);

            return returnValue;
        }
        return null;
    }


    /**
     * Copies the parameters from the stack into the local variable space of the method.
     *
     * @param md the method declaration into whose variable space this parameter will be copied
     * @throws ReflectiveOperationException is not really thrown here, but the subclasses might.
     */
    protected void setupParameters(MethodDeclaration md) throws ReflectiveOperationException {
        NodeList<Parameter> parameters = md.getParameters();

        for (int i = parameters.size() - 1; i >= 0; i--) {
            Variable va = AntikytheraRunTime.pop();
            Parameter p = parameters.get(i);
            md.getBody().ifPresent(body ->
                    setLocal(body, p.getNameAsString(), va)
            );
        }
    }

    /**
     * Execute - or rather interpret the code within a constructor found in source code
     *
     * @param md ConstructorDeclaration
     * @throws AntikytheraException         if the evaluator fails
     * @throws ReflectiveOperationException when a reflection operation fails
     */
    public void executeConstructor(CallableDeclaration<?> md) throws ReflectiveOperationException {
        if (md instanceof ConstructorDeclaration cd) {
            List<Statement> statements = cd.getBody().getStatements();
            NodeList<Parameter> parameters = md.getParameters();

            returnValue = null;
            for (int i = parameters.size() - 1; i >= 0; i--) {
                Parameter p = parameters.get(i);
                setLocal(cd.getBody(), p.getNameAsString(), AntikytheraRunTime.pop());
            }

            executeBlock(statements);

            if (!AntikytheraRunTime.isEmptyStack()) {
                AntikytheraRunTime.pop();

            }
        }
    }

    /**
     * Execute a block of statements.
     *
     * @param statements the collection of statements that make up the block
     * @throws AntikytheraException         if there are situations where we cannot process the block
     * @throws ReflectiveOperationException if a reflection operation fails
     */
    @SuppressWarnings("unchecked")
    protected void executeBlock(List<Statement> statements) throws ReflectiveOperationException {
        if (statements.isEmpty()) {
            return;
        }
        try {
            executeBlockHelper(statements);
        } catch (Exception e) {
            handleApplicationException(e, statements.getFirst().findAncestor(BlockStmt.class).orElse(null));
        }
    }

    @SuppressWarnings("unchecked")
    private void executeBlockHelper(List<Statement> statements) throws Exception {
        Evaluator.setLastException(null);
        for (Statement stmt : statements) {
            if (lastException != null) {
                throw lastException;
            }
            if (loops.isEmpty() || loops.peekLast().equals(Boolean.TRUE)) {
                executeStatement(stmt);
                if (returnFrom != null) {
                    MethodDeclaration parent = returnFrom.findAncestor(MethodDeclaration.class).orElse(null);
                    MethodDeclaration method = stmt.findAncestor(MethodDeclaration.class).orElse(null);
                    if (method == null || method.equals(parent)) {
                        break;
                    }
                }
            }
        }
    }

    /**
     * Execute a statement.
     * In the java parser architecture, a statement is not always a single line of code. They can be
     * block statements as well. For example, when an IF condition is encountered, that counts as
     * a statement. The child elements of the if statement is considered a statement but that's a
     * block as well. The same goes for the else part.
     *
     * @param stmt the statement to execute
     * @throws Exception if the execution fails.
     */
    void executeStatement(Statement stmt) throws Exception {
        if (stmt.isExpressionStmt()) {
            /*
             * A line of code that is an expression. The expression itself can fall into various different
             * categories, and we let the evaluateExpression method take care of all that
             */
            evaluateExpression(stmt.asExpressionStmt().getExpression());

        } else if (stmt.isIfStmt()) {
            /*
             * If then Else are all treated together
             */
            ifThenElseBlock(stmt.asIfStmt());

        } else if (stmt.isTryStmt()) {
            /*
             * Try takes a bit of trying
             */
            catching.addLast(stmt.asTryStmt());
            executeBlock(stmt.asTryStmt().getTryBlock().getStatements());
        } else if (stmt.isThrowStmt()) {
            /*
             * Throw is tricky because we need to distinguish between what exceptions were raised by
             * issues in Antikythera and what are exceptions that are part of the application
             */
            executeThrow(stmt);
        } else if (stmt.isReturnStmt()) {
            /*
             * Need to know if a value has been returned.
             */
            returnValue = executeReturnStatement(stmt);

        } else if (stmt.isForStmt()) {
            /*
             * Traditional for loop
             */
            executeForLoop(stmt.asForStmt());
        } else if (stmt.isForEachStmt()) {
            /*
             * Python style for each
             */
            executeForEach(stmt);
        } else if (stmt.isDoStmt()) {
            /*
             * It may not be used all that much but we still have to support do while.
             */
            executeDoWhile(stmt.asDoStmt());

        } else if (stmt.isSwitchStmt()) {
            executeSwitchStatement(stmt.asSwitchStmt());

        } else if (stmt.isWhileStmt()) {
            /*
             * Old-fashioned while statement
             */
            executeWhile(stmt.asWhileStmt());

        } else if (stmt.isBlockStmt()) {
            /*
             * in C like languages it's possible to have a block that is not directly
             * associated with a condtional, loop or method etc.
             */
            executeBlock(stmt.asBlockStmt().getStatements());
        } else if (stmt.isBreakStmt()) {
            /*
             * Breaking means signalling that the loop has to be ended for that we keep a stack
             * in with a flag for all the loops that are in our trace
             */
            loops.pollLast();
            loops.addLast(Boolean.FALSE);
        } else {
            logger.info("Unhandled statement: {}", stmt);
        }
    }

    private void executeSwitchStatement(SwitchStmt switchStmt) throws Exception {
        boolean matchFound = false;
        Statement defaultStmt = null;

        for (var entry : switchStmt.getEntries()) {
            NodeList<Expression> labels = entry.getLabels();
            for (Expression label : labels) {
                if (label.isIntegerLiteralExpr()) {
                    BinaryExpr bin = new BinaryExpr(switchStmt.getSelector(), label.asIntegerLiteralExpr(), BinaryExpr.Operator.EQUALS);
                    Variable v = evaluateExpression(bin);
                    if ((boolean) v.getValue()) {
                        executeBlock(entry.getStatements());
                        matchFound = true;
                        break;
                    }
                }
            }
            if (labels.isEmpty()) {
                defaultStmt = entry.getStatements().getFirst().orElse(null);
            }
        }

        if (!matchFound && defaultStmt != null) {
            executeStatement(defaultStmt);
        }
    }

    private void executeForEach(Statement stmt) throws ReflectiveOperationException {
        loops.addLast(true);
        ForEachStmt forEachStmt = stmt.asForEachStmt();
        Variable iter = evaluateExpression(forEachStmt.getIterable());
        Object iterValue = iter.getValue();

        if (iterValue instanceof Collection<?> list) {
            executeForEachWithCollection(list, forEachStmt);
        } else {
            executeForEachWithArray(forEachStmt, iterValue);
        }

        loops.pollLast();
    }

    private void executeForEachWithArray(ForEachStmt forEachStmt, Object iterValue) throws ReflectiveOperationException {
        evaluateExpression(forEachStmt.getVariable());

        for (int i = 0; i < Array.getLength(iterValue); i++) {
            Object value = Array.get(iterValue, i);
            for (VariableDeclarator vdecl : forEachStmt.getVariable().getVariables()) {
                Variable v = getLocal(forEachStmt, vdecl.getNameAsString());
                v.setValue(value);
            }

            executeBlock(forEachStmt.getBody().asBlockStmt().getStatements());
        }
    }

    private void executeForEachWithCollection(Collection<?> list, ForEachStmt forEachStmt) throws ReflectiveOperationException {
        for (Object value : list) {
            for (VariableDeclarator vdecl : forEachStmt.getVariable().getVariables()) {
                Variable v = getLocal(forEachStmt, vdecl.getNameAsString());
                if (v != null) {
                    v.setValue(value);
                } else {
                    v = new Variable(value);
                    setLocal(forEachStmt, vdecl.getNameAsString(), v);
                }
            }
            executeBlock(forEachStmt.getBody().asBlockStmt().getStatements());
        }
    }

    @SuppressWarnings("java:S112")
    private void executeThrow(Statement stmt) throws Exception {
        ThrowStmt t = stmt.asThrowStmt();
        if (t.getExpression().isObjectCreationExpr()) {
            ObjectCreationExpr oce = t.getExpression().asObjectCreationExpr();
            Variable v = createObject(oce);
            if (v.getValue() instanceof Exception ex) {
                throw ex;
            } else {
                throw new ByteBuddy().subclass(Exception.class)
                        .name(AbstractCompiler.findFullyQualifiedName(cu, v.getType().asString()))
                        .make()
                        .load(getClass().getClassLoader()).getLoaded().getDeclaredConstructor().newInstance();
            }
        }
    }

    private void executeForLoop(ForStmt forStmt) throws ReflectiveOperationException {
        loops.addLast(true);

        for (Node n : forStmt.getInitialization()) {
            if (n instanceof VariableDeclarationExpr vdecl) {
                evaluateExpression(vdecl);
            }
        }
        while ((boolean) evaluateExpression(forStmt.getCompare().orElseThrow()).getValue() &&
                Boolean.TRUE.equals(loops.peekLast())) {
            executeBlock(forStmt.getBody().asBlockStmt().getStatements());
            for (Node n : forStmt.getUpdate()) {
                if (n instanceof Expression e) {
                    evaluateExpression(e);
                }
            }
        }
        loops.pollLast();
    }

    private void executeDoWhile(DoStmt whileStmt) throws ReflectiveOperationException {
        loops.push(true);
        do {
            executeBlock(whileStmt.getBody().asBlockStmt().getStatements());
        } while ((boolean) evaluateExpression(whileStmt.getCondition()).getValue() && Boolean.TRUE.equals(loops.peekLast()));
        loops.pollLast();
    }

    /**
     * Execute a while loop.
     *
     * @param whileStmt the while block to execute
     * @throws AntikytheraException         if there is an error in the execution
     * @throws ReflectiveOperationException if the classes cannot be instantiated as needed with reflection
     */
    private void executeWhile(WhileStmt whileStmt) throws ReflectiveOperationException {
        loops.push(true);
        while ((boolean) evaluateExpression(whileStmt.getCondition()).getValue() && Boolean.TRUE.equals(loops.peekLast())) {
            executeBlock(whileStmt.getBody().asBlockStmt().getStatements());
        }
        loops.pollLast();
    }

    /**
     * Execute a statement that represents an If - Then or If - Then - Else
     *
     * @param ifst If / Then statement
     * @throws Exception when the condition evaluation or subsequent block execution fails
     */
    void ifThenElseBlock(IfStmt ifst) throws Exception {

        Variable v = evaluateExpression(ifst.getCondition());
        if ((boolean) v.getValue()) {
            executeStatement(ifst.getThenStmt());
        } else {
            Optional<Statement> elseBlock = ifst.getElseStmt();
            if (elseBlock.isPresent()) {
                executeStatement(elseBlock.get());
            }
        }
    }

    @SuppressWarnings("unchecked")
    protected void handleApplicationException(Exception e, BlockStmt parent) throws ReflectiveOperationException {
        returnValue = null;
        if (catching.isEmpty()) {
            throw new AUTException("Unhandled exception", e);
        }

        TryStmt t = catching.pollLast();
        Optional<TryStmt> tryStmt = parent.findAncestor(TryStmt.class);
        if (tryStmt.isPresent() && tryStmt.get().equals(t)) {
            handleApplicationException(e, t);
        }
        else {
            catching.addLast(t);
            Evaluator.setLastException(e);
        }
    }

    private void handleApplicationException(Exception e, TryStmt t) throws ReflectiveOperationException {
        boolean matchFound = false;

        for (CatchClause clause : t.getCatchClauses()) {
            if (clause.getParameter().getType().isClassOrInterfaceType()) {
                TypeWrapper wrapper = AbstractCompiler.findType(cu,
                        clause.getParameter().getType().asClassOrInterfaceType().getNameAsString());

                if (wrapper != null && isExceptionMatch(wrapper, e)) {
                    setLocal(t, clause.getParameter().getNameAsString(), new Variable(e));
                    executeBlock(clause.getBody().getStatements());
                    matchFound = true;
                    break;
                }
            }
        }

        if (t.getFinallyBlock().isPresent()) {
            executeBlock(t.getFinallyBlock().orElseThrow().getStatements());
        }

        if (!matchFound && t.getFinallyBlock().isEmpty()) {
            throw new AUTException("Unhandled exception", e);
        }
    }

    private static void setLastException(Exception e) {
        lastException = e;
    }

    private boolean isExceptionMatch(TypeWrapper wrapper, Exception e) {
        TypeDeclaration<?> decl = wrapper.getType();
        if (decl != null) {
            return e.getClass().getName().equals(decl.getFullyQualifiedName().orElse(null));
        }
        return wrapper.getClazz().isAssignableFrom(e.getClass());
    }

    Variable executeReturnStatement(Statement stmt) throws ReflectiveOperationException {
        Optional<Expression> expr = stmt.asReturnStmt().getExpression();
        if (expr.isPresent()) {
            returnValue = evaluateExpression(expr.get());
        } else {
            returnValue = null;
        }
        returnFrom = stmt.getParentNode().orElse(null);
        return returnValue;
    }

    public void setupFields() {
        cu.accept(new LazyFieldVisitor(className), null);
        processParentClasses(typeDeclaration, "LazyFieldVisitor");
        if (typeDeclaration != null) {
            typeDeclaration.getAnnotationByName("Slf4j").ifPresent(annotation -> {
                try {
                    Class<?> clazz = AKBuddy.createDynamicClass(new MethodInterceptor(this));
                    Logger log = new AKLogger(clazz);
                    fields.put("log", new Variable(log));
                } catch (ClassNotFoundException e) {
                    throw new AntikytheraException(e);
                }
            });
        }
    }

    public void initializeFields() {
        cu.accept(new FieldVisitor(className), null);
        processParentClasses(typeDeclaration, "FieldVisitor");
    }

    public String getClassName() {
        return className;
    }

    private void processParentClasses(TypeDeclaration<?> type, String visitorName) {
        if (type instanceof ClassOrInterfaceDeclaration cid) {
            for (ClassOrInterfaceType parentType : cid.getExtendedTypes()) {
                String parentClass = AbstractCompiler.findFullyQualifiedName(cu, parentType.getNameAsString());
                if (parentClass != null) {
                    CompilationUnit parentCu = AntikytheraRunTime.getCompilationUnit(parentClass);
                    if (parentCu != null) {
                        VoidVisitorAdapter<?> v = visitorName.equals("LazyFieldVisitor")
                                ? new LazyFieldVisitor(parentClass) : new FieldVisitor(parentClass);

                        parentCu.accept(v, null);

                        AbstractCompiler.getMatchingType(parentCu, parentType.getNameAsString())
                                .ifPresent(t -> processParentClasses(t, visitorName));
                    }
                }
            }
        }
    }

    void setupField(FieldDeclaration field, VariableDeclarator variableDeclarator) {
        try {
            if (field.getAnnotationByName("OneToOne").isPresent()) {
                return;
            }
            if (field.isStatic()) {
                Variable s = AntikytheraRunTime.getStaticVariable(getClassName(), variableDeclarator.getNameAsString());
                if (s != null) {
                    fields.put(variableDeclarator.getNameAsString(), s);
                    return;
                }
            }
            if (variableDeclarator.getInitializer().isEmpty()
                        && field.getAnnotationByName("Mock").isEmpty()
                        && field.getAnnotationByName("Autowired").isEmpty()
                        && !isSequenceField(field, variableDeclarator)
            ) {
                setupFieldWithoutInitializer(variableDeclarator);
                return;
            }
            Variable v = resolveVariableDeclaration(variableDeclarator);
            if (v != null) {
                checkSequences(field, variableDeclarator, v);

                fields.put(variableDeclarator.getNameAsString(), v);
                if (field.isStatic()) {
                    AntikytheraRunTime.setStaticVariable(getClassName(), variableDeclarator.getNameAsString(), v);
                }
            }
        } catch (UnsolvedSymbolException e) {
            logger.debug("ignore {}", variableDeclarator);

        } catch (ReflectiveOperationException e) {
            throw new GeneratorException(e);
        }
    }

    void setupFieldWithoutInitializer(VariableDeclarator variableDeclarator) {
        Variable nullVariable = new Variable(null);
        if (variableDeclarator.getType().isPrimitiveType()) {
            nullVariable.setValue(Reflect.getDefault(variableDeclarator.getType().asString()));
        }
        nullVariable.setType(variableDeclarator.getType());
        fields.put(variableDeclarator.getNameAsString(), nullVariable);
    }

    private void checkSequences(FieldDeclaration field, VariableDeclarator variableDeclarator, Variable v) {
        if (isSequenceField(field, variableDeclarator)) {
            incrementSequence();
            v.setValue(sequence);
            MethodCallExpr mce = new MethodCallExpr(
                    "set" + ClassProcessor.instanceToClassName(variableDeclarator.getNameAsString()));
            String type = v.getType().asString();
            if (type.equals("long") || type.equals("Long") || type.equals("java.lang.Long")) {
                mce.addArgument(new LongLiteralExpr().setValue(Long.toString(sequence) + "L"));
            }
            else {
                mce.addArgument(new IntegerLiteralExpr().setValue(Long.toString(sequence)));
            }
            v.getInitializer().add(mce);
        }
    }

    public List<Expression> getFieldInitializers() {
        List<Expression> fi = new ArrayList<>();
        for (Map.Entry<String, Variable> entry : fields.entrySet()) {
            Variable v = entry.getValue();
            if (v != null && !v.getInitializer().isEmpty()) {
                Expression first = v.getInitializer().getFirst();
                if (first instanceof MethodCallExpr m && m.getScope().isPresent()) {
                    MethodCallExpr mce = new MethodCallExpr().setName("set" + ClassProcessor.instanceToClassName(entry.getKey()));
                    mce.addArgument(first);
                    fi.add(mce);
                    for (int i = 1; i < v.getInitializer().size(); i++) {
                        mce.addArgument(v.getInitializer().get(i));
                    }
                }
                else {
                    fi.addAll(v.getInitializer());
                }
            }
        }
        return fi;
    }

    public void reset() {
        locals.clear();
    }

    @Override
    public String toString() {
        return getClass().getName() + " : " + getClassName();
    }

    public CompilationUnit getCompilationUnit() {
        return cu;
    }

    public void setCompilationUnit(CompilationUnit compilationUnit) {
        this.cu = compilationUnit;
    }

    private boolean isSequenceField(FieldDeclaration field,  VariableDeclarator variableDeclarator) {

        String typeName = variableDeclarator.getTypeAsString();
        return (field.getAnnotationByName("Id").isPresent()
                && typeDeclaration.getAnnotationByName("Entity").isPresent()
                && variableDeclarator.getInitializer().isEmpty()
                && ( typeName.equals("int") || typeName.equals("long") || typeName.equals("Integer") || typeName.equals("Long")));
    }

    private static void incrementSequence() {
        sequence++;
    }

    /**
     * <p>Java parser visitor used to set up the fields in the class.</p>
     * <p>
     * When we initialize a class the fields also need to be initialized, so here we are
     */
    private class LazyFieldVisitor extends VoidVisitorAdapter<Void> {
        String matchingClass;

        LazyFieldVisitor(String matchingClass) {
            this.matchingClass = matchingClass;
        }

        /**
         * The field visitor will be used to identify the fields that are being used in the class
         *
         * @param field the field to inspect
         * @param arg   not used
         */
        @SuppressWarnings("unchecked")
        @Override
        public void visit(FieldDeclaration field, Void arg) {
            super.visit(field, arg);
            for (var variable : field.getVariables()) {
                field.findAncestor(ClassOrInterfaceDeclaration.class).ifPresent(cdecl -> {
                    if (cdecl.getFullyQualifiedName().isPresent() && cdecl.getFullyQualifiedName().get().equals(matchingClass)) {
                        setupField(field, variable);
                    }
                });
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public void visit(EnumConstantDeclaration ecd, Void arg) {
            ecd.findAncestor(EnumDeclaration.class).ifPresent(enumDecl -> {
                if (enumDecl.getFullyQualifiedName().isPresent() && enumDecl.getFullyQualifiedName().get().equals(matchingClass)) {
                    Variable v = AntikytheraRunTime.getStaticVariable(enumDecl.getFullyQualifiedName().get(), ecd.getNameAsString());
                    if (v == null) {
                        ObjectCreationExpr oce = new ObjectCreationExpr()
                                .setType(enumDecl.getFullyQualifiedName().get());
                        for (Expression argExpr : ecd.getArguments()) {
                            oce.addArgument(argExpr);
                        }

                        Evaluator eval = EvaluatorFactory.createLazily(enumDecl.getFullyQualifiedName().get(), Evaluator.class);

                        try {
                            v = createUsingEvaluator(enumDecl, oce, eval);
                            AntikytheraRunTime.setStaticVariable(enumDecl.getFullyQualifiedName().get(), ecd.getNameAsString(), v);
                        } catch (ReflectiveOperationException e) {
                            throw new AntikytheraException(e);
                        }
                    }
                }
            });
        }
    }

    private class FieldVisitor extends VoidVisitorAdapter<Void> {
        String matchingClass;

        FieldVisitor(String matchinClass) {
            this.matchingClass = matchinClass;
        }

        @SuppressWarnings("unchecked")
        @Override
        public void visit(InitializerDeclaration init, Void arg) {
            super.visit(init, arg);
            init.findAncestor(ClassOrInterfaceDeclaration.class)
                    .flatMap(ClassOrInterfaceDeclaration::getFullyQualifiedName)
                    .ifPresent(name -> {
                        if (name.equals(matchingClass)) {
                            try {
                                executeBlock(init.getBody().getStatements());
                            } catch (ReflectiveOperationException e) {
                                throw new AntikytheraException(e);
                            }
                        }
                    });
        }
    }
}
