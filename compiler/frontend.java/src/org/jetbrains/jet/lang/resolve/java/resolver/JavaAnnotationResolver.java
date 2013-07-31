/*
 * Copyright 2010-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.lang.resolve.java.resolver;

import com.google.common.collect.Lists;
import com.intellij.codeInsight.ExternalAnnotationsManager;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.descriptors.ClassDescriptor;
import org.jetbrains.jet.lang.descriptors.ValueParameterDescriptor;
import org.jetbrains.jet.lang.descriptors.annotations.AnnotationDescriptor;
import org.jetbrains.jet.lang.resolve.constants.CompileTimeConstant;
import org.jetbrains.jet.lang.resolve.java.DescriptorResolverUtils;
import org.jetbrains.jet.lang.resolve.java.JvmAnnotationNames;
import org.jetbrains.jet.lang.resolve.java.JvmClassName;
import org.jetbrains.jet.lang.resolve.java.mapping.JavaToKotlinClassMap;
import org.jetbrains.jet.lang.resolve.java.structure.JavaAnnotation;
import org.jetbrains.jet.lang.resolve.java.structure.JavaAnnotationOwner;
import org.jetbrains.jet.lang.resolve.name.FqName;
import org.jetbrains.jet.lang.resolve.name.Name;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.jetbrains.jet.lang.resolve.java.DescriptorSearchRule.INCLUDE_KOTLIN_SOURCES;

public final class JavaAnnotationResolver {
    private JavaClassResolver classResolver;
    private JavaCompileTimeConstResolver compileTimeConstResolver;

    public JavaAnnotationResolver() {
    }

    @Inject
    public void setClassResolver(JavaClassResolver classResolver) {
        this.classResolver = classResolver;
    }

    @Inject
    public void setCompileTimeConstResolver(JavaCompileTimeConstResolver compileTimeConstResolver) {
        this.compileTimeConstResolver = compileTimeConstResolver;
    }

    @NotNull
    public List<AnnotationDescriptor> resolveAnnotations(@NotNull PsiModifierListOwner owner, @NotNull PostponedTasks tasks) {
        PsiAnnotation[] psiAnnotations = getAllAnnotations(owner);
        List<AnnotationDescriptor> r = Lists.newArrayListWithCapacity(psiAnnotations.length);
        for (PsiAnnotation psiAnnotation : psiAnnotations) {
            AnnotationDescriptor annotation = resolveAnnotation(psiAnnotation, tasks);
            if (annotation != null) {
                r.add(annotation);
            }
        }
        return r;
    }

    @NotNull
    public List<AnnotationDescriptor> resolveAnnotations(@NotNull PsiModifierListOwner owner) {
        PostponedTasks postponedTasks = new PostponedTasks();
        List<AnnotationDescriptor> annotations = resolveAnnotations(owner, postponedTasks);
        postponedTasks.performTasks();
        return annotations;
    }

    @Nullable
    public AnnotationDescriptor resolveAnnotation(PsiAnnotation psiAnnotation, @NotNull PostponedTasks postponedTasks) {
        final AnnotationDescriptor annotation = new AnnotationDescriptor();
        String qname = psiAnnotation.getQualifiedName();
        if (qname == null) {
            return null;
        }

        // Don't process internal jet annotations and jetbrains NotNull annotations
        if (qname.startsWith("jet.runtime.typeinfo.")
            || qname.equals(JvmAnnotationNames.JETBRAINS_NOT_NULL_ANNOTATION.getFqName().asString())
            || qname.equals(JvmAnnotationNames.KOTLIN_CLASS.getFqName().asString())
            || qname.equals(JvmAnnotationNames.KOTLIN_PACKAGE.getFqName().asString())
        ) {
            return null;
        }

        FqName annotationFqName = new FqName(qname);

        AnnotationDescriptor mappedClassDescriptor = JavaToKotlinClassMap.getInstance().mapToAnnotationClass(annotationFqName);
        if (mappedClassDescriptor != null) {
            return mappedClassDescriptor;
        }

        final ClassDescriptor annotationClass = classResolver.resolveClass(annotationFqName, INCLUDE_KOTLIN_SOURCES, postponedTasks);
        if (annotationClass == null) {
            return null;
        }

        postponedTasks.addTask(new Runnable() {
            @Override
            public void run() {
                annotation.setAnnotationType(annotationClass.getDefaultType());
            }
        });


        PsiAnnotationParameterList parameterList = psiAnnotation.getParameterList();
        for (PsiNameValuePair psiNameValuePair : parameterList.getAttributes()) {
            PsiAnnotationMemberValue value = psiNameValuePair.getValue();
            String name = psiNameValuePair.getName();
            if (name == null) name = "value";
            Name identifier = Name.identifier(name);

            if (value == null) return null;
            CompileTimeConstant compileTimeConst =
                    compileTimeConstResolver.getCompileTimeConstFromExpression(annotationFqName, identifier, value, postponedTasks);
            if (compileTimeConst != null) {
                ValueParameterDescriptor valueParameterDescriptor =
                        DescriptorResolverUtils.getValueParameterDescriptorForAnnotationParameter(identifier, annotationClass);
                if (valueParameterDescriptor != null) {
                    annotation.setValueArgument(valueParameterDescriptor, compileTimeConst);
                }
            }
        }

        return annotation;
    }

    @NotNull
    private static PsiAnnotation[] getAllAnnotations(@NotNull PsiModifierListOwner owner) {
        List<PsiAnnotation> result = new ArrayList<PsiAnnotation>();

        PsiModifierList list = owner.getModifierList();
        if (list != null) {
            result.addAll(Arrays.asList(list.getAnnotations()));
        }

        PsiAnnotation[] externalAnnotations = ExternalAnnotationsManager.getInstance(owner.getProject()).findExternalAnnotations(owner);
        if (externalAnnotations != null) {
            result.addAll(Arrays.asList(externalAnnotations));
        }

        return result.toArray(new PsiAnnotation[result.size()]);
    }

    @Nullable
    public static PsiAnnotation findAnnotationWithExternal(@NotNull JavaAnnotationOwner owner, @NotNull JvmClassName name) {
        JavaAnnotation annotation = owner.findAnnotation(name.getFqName());
        if (annotation != null) {
            return annotation.getPsi();
        }

        return findExternalAnnotation(owner.getPsi(), name);
    }

    public static boolean hasNotNullAnnotation(@NotNull JavaAnnotationOwner owner) {
        return findAnnotationWithExternal(owner, JvmAnnotationNames.JETBRAINS_NOT_NULL_ANNOTATION) != null;
    }

    public static boolean hasMutableAnnotation(@NotNull JavaAnnotationOwner owner) {
        return findAnnotationWithExternal(owner, JvmAnnotationNames.JETBRAINS_MUTABLE_ANNOTATION) != null;
    }

    public static boolean hasReadonlyAnnotation(@NotNull JavaAnnotationOwner owner) {
        return findAnnotationWithExternal(owner, JvmAnnotationNames.JETBRAINS_READONLY_ANNOTATION) != null;
    }


    @Nullable
    public static PsiAnnotation findOwnAnnotation(@NotNull PsiModifierListOwner owner, @NotNull JvmClassName name) {
        PsiModifierList list = owner.getModifierList();
        return list == null ? null : list.findAnnotation(name.getFqName().asString());
    }

    @Nullable
    public static PsiAnnotation findExternalAnnotation(@NotNull PsiModifierListOwner owner, @NotNull JvmClassName name) {
        return ExternalAnnotationsManager.getInstance(owner.getProject()).findExternalAnnotation(owner, name.getFqName().asString());
    }
}