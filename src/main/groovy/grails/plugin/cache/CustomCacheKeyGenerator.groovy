/* Copyright 2013 SpringSource.
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
package grails.plugin.cache

import grails.plugins.GrailsVersionUtils
import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import org.springframework.aop.framework.AopProxyUtils
import org.springframework.cache.interceptor.KeyGenerator
import org.springframework.core.SpringVersion
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.StandardEvaluationContext

import java.io.Serializable
import java.lang.reflect.Method
import java.util.Map

/**
 * Includes the hashcode, method signature, and class name of the target (caller) in the cache key
 */
@CompileStatic
class CustomCacheKeyGenerator implements KeyGenerator, GrailsCacheKeyGenerator {
	
	private final KeyGenerator innerKeyGenerator

	CustomCacheKeyGenerator(KeyGenerator innerKeyGenerator){
		this.innerKeyGenerator = innerKeyGenerator
	}

	CustomCacheKeyGenerator(){
		// Use the Spring key generator if the Spring version is 4.0.3 or later
		// Can't use the Spring key generator if < 4.0.3 because of https://jira.spring.io/browse/SPR-11505
		if(SpringVersion.getVersion()==null || GrailsVersionUtils.isVersionGreaterThan(SpringVersion.getVersion(),"4.0.3")){
			this.innerKeyGenerator = new SimpleKeyGenerator()
		}else{
			try {
				this.innerKeyGenerator = (KeyGenerator) Class.forName("org.springframework.cache.interceptor.SimpleKeyGenerator").newInstance()
			} catch (Exception e) {
				// this should never happen
				throw new RuntimeException(e)
			}
		}
	}



	@SuppressWarnings("serial")
	@EqualsAndHashCode
	private static final class CacheKey implements Serializable {
		final String targetClassName
		final String targetMethodName
		final int targetObjectHashCode
		final Object simpleKey

		CacheKey(String targetClassName, String targetMethodName,
				 int targetObjectHashCode, Object simpleKey) {
			this.targetClassName = targetClassName
			this.targetMethodName = targetMethodName
			this.targetObjectHashCode = targetObjectHashCode
			this.simpleKey = simpleKey
		}
	}

	Object generate(Object target, Method method, Object... params) {
		Class<?> objClass = AopProxyUtils.ultimateTargetClass(target)

		return new CacheKey(
				objClass.getName().intern(),
				method.toString().intern(),
				target.hashCode(), innerKeyGenerator.generate(target, method, params))
	}

	@Override
	Serializable generateFromClosure(String className, String methodName, int objHashCode, Closure keyGenerator) {
		final Object simpleKey = keyGenerator.call()
		return new TemporaryGrailsCacheKey(className, methodName, objHashCode, simpleKey)
	}

	@Override
	Serializable generateFromParameters(String className, String methodName, int objHashCode, Map methodParams) {
		final Object simpleKey = methodParams
		return new TemporaryGrailsCacheKey(className, methodName, objHashCode, simpleKey)
	}


	@EqualsAndHashCode
	@CompileStatic
	private static class TemporaryGrailsCacheKey implements Serializable {
		final String targetClassName
		final String targetMethodName
		final int targetObjectHashCode
		final Object simpleKey

		TemporaryGrailsCacheKey(String targetClassName, String targetMethodName,
								int targetObjectHashCode, Object simpleKey) {
			this.targetClassName = targetClassName
			this.targetMethodName = targetMethodName
			this.targetObjectHashCode = targetObjectHashCode
			this.simpleKey = simpleKey
		}
	}

}

