package com.axellience.vuegwt.jsr69.component;

import com.axellience.vuegwt.client.definitions.VueComponentDefinition;
import com.axellience.vuegwt.client.definitions.VueDefinitionCache;
import com.axellience.vuegwt.client.definitions.component.ComputedKind;
import com.axellience.vuegwt.client.definitions.component.DataDefinition;
import com.axellience.vuegwt.client.jsnative.types.JsArray;
import com.axellience.vuegwt.jsr69.GenerationUtil;
import com.axellience.vuegwt.jsr69.component.annotations.Component;
import com.axellience.vuegwt.jsr69.component.annotations.Computed;
import com.axellience.vuegwt.jsr69.component.annotations.Prop;
import com.axellience.vuegwt.jsr69.component.annotations.PropValidator;
import com.axellience.vuegwt.jsr69.component.annotations.Watch;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeSpec.Builder;
import jsinterop.annotations.JsType;

import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypesException;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Generate VueComponentDefinitions from the user VueComponent classes
 * @author Adrien Baron
 */
public class VueComponentDefinitionGenerator
{
    private static String COMPONENT_DEFINITION_SUFFIX = "_ComponentDefinition";
    private static String JCI = "vuegwt$javaComponentInstance";

    private static Map<String, Boolean> LIFECYCLE_HOOKS_MAP = new HashMap<>();

    static
    {
        // Init the map of lifecycle hooks for fast type type check
        LIFECYCLE_HOOKS_MAP.put("beforeCreate", true);
        LIFECYCLE_HOOKS_MAP.put("created", true);
        LIFECYCLE_HOOKS_MAP.put("beforeMount", true);
        LIFECYCLE_HOOKS_MAP.put("mounted", true);
        LIFECYCLE_HOOKS_MAP.put("beforeUpdate", true);
        LIFECYCLE_HOOKS_MAP.put("updated", true);
        LIFECYCLE_HOOKS_MAP.put("activated", true);
        LIFECYCLE_HOOKS_MAP.put("deactivated", true);
        LIFECYCLE_HOOKS_MAP.put("beforeDestroy", true);
        LIFECYCLE_HOOKS_MAP.put("destroyed", true);
    }

    private final Elements elementsUtils;
    private final Filer filer;

    public VueComponentDefinitionGenerator(ProcessingEnvironment processingEnv)
    {
        elementsUtils = processingEnv.getElementUtils();
        filer = processingEnv.getFiler();
    }

    /**
     * Generate and save the Java file for the typeElement passed to the constructor
     */
    public void generate(TypeElement componentTypeElement)
    {
        String packageName =
            elementsUtils.getPackageOf(componentTypeElement).getQualifiedName().toString();
        String typeName = componentTypeElement.getSimpleName().toString();
        String generatedTypeName = typeName + COMPONENT_DEFINITION_SUFFIX;

        Component annotation = componentTypeElement.getAnnotation(Component.class);

        Builder componentClassBuilder = TypeSpec
            .classBuilder(generatedTypeName)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .superclass(VueComponentDefinition.class)
            .addAnnotation(JsType.class)
            .addJavadoc("Vue Component Definition for component {@link $S}",
                componentTypeElement.getQualifiedName().toString());

        // Static init block
        componentClassBuilder.addStaticBlock(CodeBlock.of(
            "$T.registerComponent($T.class, new $L());",
            VueDefinitionCache.class,
            TypeName.get(componentTypeElement.asType()),
            generatedTypeName));

        // Initialize constructor
        MethodSpec.Builder constructorBuilder =
            MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);

        // Add the Java Component Instance initialization
        constructorBuilder.addStatement("this.$L = new $T()",
            JCI,
            TypeName.get(componentTypeElement.asType()));

        // Set the name of the component
        if (!"".equals(annotation.name()))
        {
            constructorBuilder.addStatement("this.name = $S", annotation.name());
        }

        // Add template initialization
        constructorBuilder.addStatement("this.setTemplateResource($T.INSTANCE.$L())",
            ClassName.get(packageName, typeName + TemplateProviderGenerator.TEMPLATE_BUNDLE_SUFFIX),
            TemplateProviderGenerator.TEMPLATE_BUNDLE_METHOD_NAME);

        // Data and props
        constructorBuilder.addStatement("$T<$T> dataFields = new $T<>()",
            List.class,
            DataDefinition.class,
            LinkedList.class);
        ElementFilter
            .fieldsIn(componentTypeElement.getEnclosedElements())
            .forEach(variableElement ->
            {
                String javaName = variableElement.getSimpleName().toString();
                Prop prop = variableElement.getAnnotation(Prop.class);

                if (prop != null)
                {
                    constructorBuilder.addStatement("this.addProp($S, $S, $L, $S)",
                        javaName,
                        !"".equals(prop.propertyName()) ? prop.propertyName() : javaName,
                        prop.required(),
                        prop.checkType() ? getNativeNameForJavaType(variableElement.asType()) :
                            null);
                }
                else
                {
                    constructorBuilder.addStatement("dataFields.add(new $T($S))",
                        DataDefinition.class,
                        javaName);
                }
            });
        constructorBuilder.addStatement("this.initData(dataFields, $L)", annotation.useFactory());

        // Methods
        ElementFilter
            .methodsIn(componentTypeElement.getEnclosedElements())
            .forEach(executableElement ->
            {
                String javaName = executableElement.getSimpleName().toString();
                Computed computed = executableElement.getAnnotation(Computed.class);
                Watch watch = executableElement.getAnnotation(Watch.class);
                PropValidator propValidator = executableElement.getAnnotation(PropValidator.class);

                if (computed != null)
                {
                    addComputed(javaName, computed, executableElement, constructorBuilder);
                }
                else if (watch != null)
                {
                    String jsName = watch.propertyName();
                    constructorBuilder.addStatement("this.addWatch($S, $S, $L)",
                        javaName,
                        jsName,
                        watch.isDeep());
                }
                else if (propValidator != null)
                {
                    String propertyName = propValidator.propertyName();
                    constructorBuilder.addStatement("this.addPropValidator($S, $S)",
                        javaName,
                        propertyName);
                }
                else if (LIFECYCLE_HOOKS_MAP.containsKey(javaName))
                {
                    constructorBuilder.addStatement("this.addLifecycleHook($S)", javaName);
                }
                else
                {
                    constructorBuilder.addStatement("this.addMethod($S)", javaName);
                }
            });

        registerLocalComponents(annotation, constructorBuilder);

        registerLocalDirectives(annotation, constructorBuilder);

        // Finish building the constructor and add to the component definition
        componentClassBuilder.addMethod(constructorBuilder.build());

        // Build the component definition class
        GenerationUtil.toJavaFile(filer,
            componentClassBuilder,
            packageName,
            generatedTypeName,
            componentTypeElement);
    }

    /**
     * Register components passed to the annotation
     * @param annotation
     * @param constructorBuilder
     */
    private void registerLocalComponents(Component annotation, MethodSpec.Builder constructorBuilder)
    {
        // Components
        try
        {
            Class<?>[] componentsClass = annotation.components();
            Stream
                .of(componentsClass)
                .forEach(clazz -> constructorBuilder.addStatement("this.addLocalComponent($L.class)",
                    clazz.getCanonicalName()));
        }
        catch (MirroredTypesException mte)
        {
            List<DeclaredType> classTypeMirrors = (List<DeclaredType>) mte.getTypeMirrors();
            classTypeMirrors.forEach(classTypeMirror ->
            {
                TypeElement classTypeElement = (TypeElement) classTypeMirror.asElement();
                constructorBuilder.addStatement("this.addLocalComponent($L.class)",
                    classTypeElement.getQualifiedName().toString());
            });
        }
    }

    /**
     * Register directives passed to the annotation
     * @param annotation
     * @param constructorBuilder
     */
    private void registerLocalDirectives(Component annotation, MethodSpec.Builder constructorBuilder)
    {
        // Directives
        try
        {
            Class<?>[] directivesClass = annotation.directives();
            Stream
                .of(directivesClass)
                .forEach(clazz -> constructorBuilder.addStatement("this.addLocalDirective($L.class)",
                    clazz.getCanonicalName()));
        }
        catch (MirroredTypesException mte)
        {
            List<DeclaredType> classTypeMirrors = (List<DeclaredType>) mte.getTypeMirrors();
            classTypeMirrors.forEach(classTypeMirror ->
            {
                TypeElement classTypeElement = (TypeElement) classTypeMirror.asElement();
                constructorBuilder.addStatement("this.addLocalDirective($L.class)",
                    classTypeElement.getQualifiedName().toString());
            });
        }
    }

    private void addComputed(String javaName, Computed computed, ExecutableElement method,
        MethodSpec.Builder constructorBuilder)
    {
        ComputedKind kind = ComputedKind.GETTER;
        if ("void".equals(method.getReturnType().toString()))
            kind = ComputedKind.SETTER;

        constructorBuilder.addStatement("this.addComputed($S, $S, $T.$L)",
            javaName,
            GenerationUtil.getComputedPropertyName(computed, method.getSimpleName().toString()),
            ComputedKind.class,
            kind);
    }

    private String getNativeNameForJavaType(TypeMirror typeMirror)
    {
        TypeName typeName = TypeName.get(typeMirror);

        if (typeName.equals(TypeName.INT)
            || typeName.equals(TypeName.BYTE)
            || typeName.equals(TypeName.SHORT)
            || typeName.equals(TypeName.LONG)
            || typeName.equals(TypeName.FLOAT)
            || typeName.equals(TypeName.DOUBLE))
        {
            return "Number";
        }
        else if (typeName.equals(TypeName.BOOLEAN))
        {
            return "Boolean";
        }
        else if (typeName.equals(TypeName.get(String.class)) || typeName.equals(TypeName.CHAR))
        {
            return "String";
        }
        else if (typeMirror.toString().startsWith(JsArray.class.getCanonicalName()))
        {
            return "Array";
        }
        else
        {
            return "Object";
        }
    }
}
