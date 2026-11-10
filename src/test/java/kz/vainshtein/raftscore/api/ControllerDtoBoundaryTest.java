package kz.vainshtein.raftscore.api;

import jakarta.persistence.Entity;
import kz.vainshtein.raftscore.RaftScoreApplication;
import kz.vainshtein.raftscore.auth.entities.User;
import kz.vainshtein.raftscore.auth.models.AuthenticatedPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ControllerDtoBoundaryTest {

    @Test
    void applicationControllerHandlersDoNotDeclareEntityResponses() throws ClassNotFoundException {
        var handlersChecked = 0;
        for (var controllerType : productionControllers()) {
            var handlers = MethodIntrospector.selectMethods(controllerType,
                    (MethodIntrospector.MetadataLookup<Boolean>) method ->
                            AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class) ? true : null);
            for (var handler : handlers.keySet()) {
                handlersChecked++;
                assertThat(containsEntity(ResolvableType.forMethodReturnType(handler, controllerType)))
                        .as("Controller handler %s must map entities to DTOs", handler)
                        .isFalse();
            }
        }
        assertThat(handlersChecked).as("mapped production controller handlers checked").isPositive();
    }

    private static List<Class<?>> productionControllers() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        var controllers = scanner.findCandidateComponents("kz.vainshtein.raftscore");
        var productionLocation = RaftScoreApplication.class.getProtectionDomain().getCodeSource().getLocation();
        var result = new ArrayList<Class<?>>();
        for (var controller : controllers) {
            var controllerType = Class.forName(controller.getBeanClassName());
            if (productionLocation.equals(controllerType.getProtectionDomain().getCodeSource().getLocation())) {
                result.add(controllerType);
            }
        }
        return result;
    }

    @Test
    void excludesTestControllersFromProductionScan() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        assertThat(scanner.findCandidateComponents("kz.vainshtein.raftscore.api"))
                .anyMatch(candidate -> TestController.class.getName().equals(candidate.getBeanClassName()));
        assertThat(productionControllers()).doesNotContain(TestController.class).isNotEmpty();
    }

    @Test
    void recognizesEntityTypesInsideResponseWrappers() throws NoSuchMethodException {
        assertThat(containsEntity(returnType("directEntity"))).isTrue();
        assertThat(containsEntity(returnType("wrappedEntities"))).isTrue();
        assertThat(containsEntity(returnType("entityArray"))).isTrue();
        assertThat(containsEntity(returnType("dtoResponse"))).isFalse();
        assertThat(containsEntity(returnType("recursiveBound"))).isFalse();
    }

    private static ResolvableType returnType(String methodName) throws NoSuchMethodException {
        Method method = ExampleResponses.class.getDeclaredMethod(methodName);
        return ResolvableType.forMethodReturnType(method);
    }

    private static boolean containsEntity(ResolvableType type) {
        return containsEntity(type, new HashSet<>());
    }

    private static boolean containsEntity(ResolvableType type, Set<Type> path) {
        if (!path.add(type.getType())) {
            return false;
        }
        try {
            return containsEntityInType(type, path);
        } finally {
            path.remove(type.getType());
        }
    }

    private static boolean containsEntityInType(ResolvableType type, Set<Type> path) {
        var rawType = type.resolve();
        if (rawType != null && rawType.isAnnotationPresent(Entity.class)) {
            return true;
        }
        if (type.isArray() && containsEntity(type.getComponentType(), path)) {
            return true;
        }
        for (var generic : type.getGenerics()) {
            if (containsEntity(generic, path)) {
                return true;
            }
        }
        return false;
    }

    private interface ExampleResponses {
        User directEntity();
        ResponseEntity<Map<String, List<User>>> wrappedEntities();
        User[] entityArray();
        ResponseEntity<List<AuthenticatedPrincipal>> dtoResponse();
        <T extends Enum<T>> T recursiveBound();
    }

    @RestController
    static class TestController {
        @RequestMapping("/test-entity")
        User entity() {
            return null;
        }
    }
}
