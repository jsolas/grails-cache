/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.grails.plugin.cache.compiler

import grails.compiler.ast.GrailsArtefactClassInjector
import groovy.transform.CompileStatic
import groovy.transform.TypeChecked
import org.codehaus.groovy.ast.*
import org.codehaus.groovy.ast.expr.*
import org.codehaus.groovy.ast.stmt.*
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.syntax.Token
import org.codehaus.groovy.syntax.Types
import org.codehaus.groovy.transform.ASTTransformation
import org.codehaus.groovy.transform.GroovyASTTransformation
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.CacheManager
import org.springframework.cache.interceptor.KeyGenerator

import java.lang.reflect.Modifier

import static org.grails.compiler.injection.GrailsASTUtils.copyParameters
import static org.grails.compiler.injection.GrailsASTUtils.processVariableScopes

/**
 * @since 4.0.0
 */
@GroovyASTTransformation(phase = CompilePhase.CANONICALIZATION)
@CompileStatic
public class CacheableTransformation implements ASTTransformation {

    public static final ClassNode COMPILE_STATIC_TYPE = ClassHelper.make(CompileStatic)
    public static final ClassNode TYPE_CHECKED_TYPE = ClassHelper.make(TypeChecked)

    public static final String GRAILS_CACHE_MANAGER_PROPERTY_NAME = 'grailsCacheManager'
    public static final String CUSTOM_CACHE_KEY_GENERATOR_PROPERTY_NAME = 'customCacheKeyGenerator'
    public static final String CACHE_VALUE_WRAPPER_LOCAL_VARIABLE_NAME = '$_cache_valueWrapper'
    public static final String CACHE_CACHE_KEY_LOCAL_VARIABLE_NAME = '$_cache_cacheKey'
    public static final String CACHE_CACHE_VARIABLE_LOCAL_VARIABLE_NAME = '$_cache_cacheVariable'
    public static final String CACHE_METHOD_REFERENCE_LOCAL_VARIABLE_NAME = '$_cache_methodReference'
    public static final String CACHE_ORIGINAL_METHOD_RETURN_VALUE_LOCAL_VARIABLE_NAME = '$_cache_originalMethodReturnValue'

    @Override
    public void visit(final ASTNode[] astNodes, final SourceUnit sourceUnit) {
        final ASTNode firstNode = astNodes[0];
        final ASTNode secondNode = astNodes[1];
        if (!(firstNode instanceof AnnotationNode) || !(secondNode instanceof AnnotatedNode)) {
            throw new RuntimeException("Internal error: wrong types: " + firstNode.getClass().getName() +
                    " / " + secondNode.getClass().getName());
        }

        final AnnotationNode grailsCacheAnnotationNode = (AnnotationNode) firstNode;
        final AnnotatedNode annotatedNode = (AnnotatedNode) secondNode;

        if(annotatedNode instanceof MethodNode) {
            MethodNode methodNode = (MethodNode)annotatedNode
            ClassNode declaringClass = methodNode.getDeclaringClass()
            configureCachingForMethod(declaringClass, grailsCacheAnnotationNode, methodNode, sourceUnit)
            addAutowiredPropertiesToClass(declaringClass)
        } else {
            // TODO
            // still need to deal with annotation on a class...
        }
    }

    protected void addAutowiredPropertiesToClass(ClassNode classNode) {
        addAutowiredPropertyToClass classNode, CacheManager, GRAILS_CACHE_MANAGER_PROPERTY_NAME
        addAutowiredPropertyToClass classNode, KeyGenerator, CUSTOM_CACHE_KEY_GENERATOR_PROPERTY_NAME
    }

    protected addAutowiredPropertyToClass(ClassNode classNode, Class propertyType, String propertyName) {
        if(!classNode.hasProperty(propertyName)) {
            FieldNode cacheManagerFieldNode = new FieldNode(propertyName, Modifier.PRIVATE, ClassHelper.make(propertyType), classNode, new EmptyExpression())
            AnnotationNode autowiredAnnotationNode = new AnnotationNode(ClassHelper.make(Autowired))
            autowiredAnnotationNode.setMember('required', new ConstantExpression(false))
            cacheManagerFieldNode.addAnnotation(autowiredAnnotationNode)
            PropertyNode cacheManagerPropertyNode = new PropertyNode(cacheManagerFieldNode, Modifier.PUBLIC, null, null)
            classNode.addProperty(cacheManagerPropertyNode)
        }
    }

    protected void configureCachingForMethod(ClassNode declaringClass, AnnotationNode cacheAnnotationOnMethod, MethodNode methodToCache, SourceUnit sourceUnit) {
        Expression expressionToCallOriginalMethod = moveOriginalCodeToNewMethod(sourceUnit, declaringClass, methodToCache)

        BlockStatement cachingCode = new BlockStatement()

        addCodeToExecuteIfCacheManagerIsNull(expressionToCallOriginalMethod, cachingCode)
        addCodeToRetrieveCache(cacheAnnotationOnMethod, cachingCode)
        addCodeToInitializeCacheKey(declaringClass, methodToCache, cachingCode)
        addCodeToRetrieveWrapperFromCache(cachingCode)

        Expression valueWrapperVariableExpression = new VariableExpression(CACHE_VALUE_WRAPPER_LOCAL_VARIABLE_NAME)
        BlockStatement wrapperNotNullBlock = getCodeToExecuteIfWrapperExistsInCache()
        BlockStatement wrapperIsNullBlock = getCodeToExecuteIfWrapperIsNull(expressionToCallOriginalMethod)

        Statement ifValueWrapperStatement = new IfStatement(new BooleanExpression(valueWrapperVariableExpression), wrapperNotNullBlock, wrapperIsNullBlock)
        cachingCode.addStatement(ifValueWrapperStatement)

        methodToCache.code = cachingCode
    }

    protected BlockStatement getCodeToExecuteIfWrapperExistsInCache() {
        BlockStatement wrapperNotNullBlock = new BlockStatement()
        Expression valueWrapperVariableExpression = new VariableExpression(CACHE_VALUE_WRAPPER_LOCAL_VARIABLE_NAME)
        Expression getValueFromWrapperMethodCallExpression = new MethodCallExpression(valueWrapperVariableExpression, 'get', new ArgumentListExpression())
        wrapperNotNullBlock.addStatement(new ReturnStatement(getValueFromWrapperMethodCallExpression))
        wrapperNotNullBlock
    }

    protected void addCodeToRetrieveWrapperFromCache(BlockStatement codeBlock) {
        VariableExpression cacheKeyVariableExpression = new VariableExpression(CACHE_CACHE_KEY_LOCAL_VARIABLE_NAME)
        Expression cacheVariableExpression = new VariableExpression(CACHE_CACHE_VARIABLE_LOCAL_VARIABLE_NAME)
        Expression getValueWrapperMethodCallExpression = new MethodCallExpression(cacheVariableExpression, 'get', cacheKeyVariableExpression)
        Expression valueWrapperVariableExpression = new VariableExpression(CACHE_VALUE_WRAPPER_LOCAL_VARIABLE_NAME)
        Expression declareValueWrapperExpression = new DeclarationExpression(valueWrapperVariableExpression, Token.newSymbol(Types.EQUALS, 0, 0), getValueWrapperMethodCallExpression)

        codeBlock.addStatement(new ExpressionStatement(declareValueWrapperExpression))
    }

    protected void addCodeToExecuteIfCacheManagerIsNull(MethodCallExpression expressionToCallOriginalMethod, BlockStatement codeBlock) {
        VariableExpression cacheManagerVariableExpression = new VariableExpression(GRAILS_CACHE_MANAGER_PROPERTY_NAME)
        Statement ifCacheManager = new IfStatement(new BooleanExpression(cacheManagerVariableExpression), new EmptyStatement(), new ReturnStatement(expressionToCallOriginalMethod))

        codeBlock.addStatement(ifCacheManager)
    }

    protected void addCodeToRetrieveCache(AnnotationNode cacheAnnotationOnMethod, BlockStatement codeBlock) {
        VariableExpression cacheManagerVariableExpression = new VariableExpression(GRAILS_CACHE_MANAGER_PROPERTY_NAME)
        Expression cacheVariableExpression = new VariableExpression(CACHE_CACHE_VARIABLE_LOCAL_VARIABLE_NAME)
        Expression cacheNameExpression = (ConstantExpression) cacheAnnotationOnMethod.getMember('value')
        Expression getCacheMethodCallExpression = new MethodCallExpression(cacheManagerVariableExpression, 'getCache', new ArgumentListExpression(cacheNameExpression))
        Expression declareCacheExpression = new DeclarationExpression(cacheVariableExpression, Token.newSymbol(Types.EQUALS, 0, 0), getCacheMethodCallExpression)

        codeBlock.addStatement(new ExpressionStatement(declareCacheExpression))
    }

    protected void addCodeToInitializeCacheKey(ClassNode declaringClass, MethodNode methodToCache, BlockStatement codeBlock) {
        ClassExpression classExpression = new ClassExpression(declaringClass)
        ArgumentListExpression args = new ArgumentListExpression()
        args.addExpression(new ConstantExpression(methodToCache.getName()))

        List<Expression> parameterTypes = new ArrayList<Expression>()
        Parameter[] parameters = methodToCache.getParameters()
        for (Parameter p : parameters) {
            parameterTypes.add(new ClassExpression(p.type))
        }
        ArrayExpression getDeclaredMethodArgs = new ArrayExpression(ClassHelper.make(Class), parameterTypes)
        args.addExpression(getDeclaredMethodArgs)

        MethodCallExpression getDeclaredMethodExpression = new MethodCallExpression(classExpression, 'getDeclaredMethod', args)

        VariableExpression methodReferenceVariableExpression = new VariableExpression(CACHE_METHOD_REFERENCE_LOCAL_VARIABLE_NAME)
        DeclarationExpression declareMethodReferenceExpression = new DeclarationExpression(methodReferenceVariableExpression, Token.newSymbol(Types.EQUALS, 0, 0), getDeclaredMethodExpression)

        ArgumentListExpression generateKeyArgs = new ArgumentListExpression()
        generateKeyArgs.addExpression(new VariableExpression('this'))
        generateKeyArgs.addExpression(methodReferenceVariableExpression)

        List<Expression> parameterList = new ArrayList<Expression>()
        for (Parameter p : parameters) {
            parameterList.add(new VariableExpression(p.getName()))
        }
        ArrayExpression generateArgs = new ArrayExpression(ClassHelper.make(Object), parameterList)
        generateKeyArgs.addExpression(generateArgs)
        Expression cacheKeyExpression = new MethodCallExpression(new VariableExpression(CUSTOM_CACHE_KEY_GENERATOR_PROPERTY_NAME), 'generate', generateKeyArgs)

        VariableExpression cacheKeyVariableExpression = new VariableExpression(CACHE_CACHE_KEY_LOCAL_VARIABLE_NAME)
        DeclarationExpression cacheKeyDeclaration = new DeclarationExpression(cacheKeyVariableExpression, Token.newSymbol(Types.EQUALS, 0, 0), cacheKeyExpression)

        codeBlock.addStatement(new ExpressionStatement(declareMethodReferenceExpression))
        codeBlock.addStatement(new ExpressionStatement(cacheKeyDeclaration))
    }

    protected BlockStatement getCodeToExecuteIfWrapperIsNull(MethodCallExpression expressionToCallOriginalMethod) {
        BlockStatement wrapperIsNullBlock = new BlockStatement()

        Expression cacheKeyVariableExpression = new VariableExpression(CACHE_CACHE_KEY_LOCAL_VARIABLE_NAME)
        Expression cacheVariableExpression = new VariableExpression(CACHE_CACHE_VARIABLE_LOCAL_VARIABLE_NAME)
        Expression returnValueVariableExpression = new VariableExpression(CACHE_ORIGINAL_METHOD_RETURN_VALUE_LOCAL_VARIABLE_NAME)
        Expression initializeReturnValueExpression = new DeclarationExpression(returnValueVariableExpression, Token.newSymbol(Types.EQUALS, 0, 0), expressionToCallOriginalMethod)
        ArgumentListExpression putArgs = new ArgumentListExpression()
        putArgs.addExpression(cacheKeyVariableExpression)
        putArgs.addExpression(returnValueVariableExpression)
        Expression updateCache = new MethodCallExpression(cacheVariableExpression, 'put', putArgs)
        wrapperIsNullBlock.addStatement(new ExpressionStatement(initializeReturnValueExpression))
        wrapperIsNullBlock.addStatement(new ExpressionStatement(updateCache))
        wrapperIsNullBlock.addStatement(new ReturnStatement(returnValueVariableExpression))
        wrapperIsNullBlock
    }

    protected MethodCallExpression moveOriginalCodeToNewMethod(SourceUnit source, ClassNode classNode, MethodNode methodNode) {
        String renamedMethodName = '$$_cache_' + methodNode.getName()
        def newParameters = methodNode.getParameters() ? (copyParameters(((methodNode.getParameters() as List)) as Parameter[])) : new Parameter[0]

        MethodNode renamedMethodNode = new MethodNode(
                renamedMethodName,
                Modifier.PROTECTED, methodNode.getReturnType().getPlainNodeReference(),
                newParameters,
                GrailsArtefactClassInjector.EMPTY_CLASS_ARRAY,
                methodNode.code
        );

        // GrailsCompileStatic and GrailsTypeChecked are not explicitly addressed
        // here but they will be picked up because they are @AnnotationCollector annotations
        // which use CompileStatic and TypeChecked...
        renamedMethodNode.addAnnotations(methodNode.getAnnotations(COMPILE_STATIC_TYPE))
        renamedMethodNode.addAnnotations(methodNode.getAnnotations(TYPE_CHECKED_TYPE))

        methodNode.setCode(null)
        classNode.addMethod(renamedMethodNode)

        processVariableScopes(source, classNode, renamedMethodNode)

        final originalMethodCall = new MethodCallExpression(new VariableExpression("this"), renamedMethodName, new ArgumentListExpression(renamedMethodNode.parameters))
        originalMethodCall.setImplicitThis(false)
        originalMethodCall.setMethodTarget(renamedMethodNode)

        originalMethodCall
    }
}