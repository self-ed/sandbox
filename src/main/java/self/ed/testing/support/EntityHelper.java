package self.ed.testing.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.PersistenceContext;
import javax.persistence.criteria.*;
import javax.persistence.metamodel.SingularAttribute;
import java.util.*;
import java.util.function.Consumer;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.reflect.FieldUtils.readField;
import static self.ed.testing.support.RandomUtils.random;

/**
 * @author Anatolii
 */
@Repository
@Transactional
public class EntityHelper {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    public <T> T create(Class<T> clazz) {
        return create(clazz, e -> {
            // no-op
        });
    }

    public <T> T create(Class<T> clazz, Consumer<T> callback) {
        T entity = random(clazz, getIdFieldName(clazz));
        callback.accept(entity);
        entityManager.persist(entity);
        return entity;
    }

    @SuppressWarnings("unchecked")
    public <T> T find(T entity, String... lazyFieldsToInitialize) {
        return find((Class<T>) entity.getClass(), getId(entity), lazyFieldsToInitialize);
    }

    public <T> T find(Class<T> clazz, Object id, String... lazyFieldsToInitialize) {
        T entity = entityManager.find(clazz, id);
        initializeLazyFields(entity, lazyFieldsToInitialize);
        return entity;
    }

    public <T> List<T> findAll(Class<T> clazz) {
        return findAll(clazz, emptyMap());
    }

    public <T> List<T> findAll(Class<T> clazz, Map<String, Object> whereParams, String... lazyFieldsToInitialize) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(clazz);
        Root<T> rootEntity = query.from(clazz);
        query.select(rootEntity).where(buildWhereClause(cb, rootEntity, whereParams));
        List<T> resultList = entityManager.createQuery(query).getResultList();
        resultList.forEach(entity -> initializeLazyFields(entity, lazyFieldsToInitialize));
        return resultList;
    }

    public void remove(Object entity) {
        entityManager.remove(entity);
    }

    public <T> void removeAll(Class<T> clazz) {
        removeAll(clazz, emptyMap());
    }

    public <T> void removeAll(Class<T> clazz, Map<String, Object> whereParams) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaDelete<T> criteriaDelete = cb.createCriteriaDelete(clazz);
        Root<T> rootDelete = criteriaDelete.from(clazz);
        // Using subquery to support nested keys in where conditions like "fieldOne.fieldTwo.fieldThree" = "valueThree",
        // which result in cross joins not supported by delete statement itself
        Subquery<T> subquery = criteriaDelete.subquery(clazz);
        Root<T> rootSubquery = subquery.from(clazz);
        subquery.select(rootSubquery).where(buildWhereClause(cb, rootSubquery, whereParams));
        criteriaDelete.where(cb.in(rootDelete).value(subquery));
        entityManager.createQuery(criteriaDelete).executeUpdate();
    }

    public void merge(Object entity) {
        entityManager.merge(entity);
    }

    private Object getId(Object entity) {
        return entityManagerFactory.getPersistenceUnitUtil().getIdentifier(entity);
    }

    private String getIdFieldName(Class<?> clazz) {
        return entityManagerFactory.getMetamodel()
                .entity(clazz)
                .getSingularAttributes()
                .stream()
                .filter(SingularAttribute::isId)
                .findFirst()
                .map(SingularAttribute::getName)
                .orElseThrow(() -> new IllegalArgumentException("Cannot get id field name for " + clazz));
    }

    private void initializeLazyFields(Object entity, String... lazyFieldsToInitialize) {
        if (entity != null) {
            asList(lazyFieldsToInitialize).forEach(field -> initialize(getFieldValue(entity, field)));
        }
    }

    private static void initialize(Object lazyField) {
        // With Hibernate it would be a peace of cake!
        if (Iterable.class.isInstance(lazyField)) {
            Iterable.class.cast(lazyField).iterator();
        } else if (Map.class.isInstance(lazyField)) {
            Map.class.cast(lazyField).size();
        } else if (lazyField != null) {
            throw new IllegalArgumentException("Don't know how to initialize " + lazyField);
        }
    }

    private Expression<Boolean> buildWhereClause(CriteriaBuilder criteriaBuilder, Root<?> rootEntity, Map<String, Object> whereParams) {
        Predicate[] restrictions = whereParams.entrySet().stream()
                .map(entry -> {
                    Path<?> key = rootEntity;
                    for (String fieldName : entry.getKey().split("\\.")) {
                        key = key.get(fieldName);
                    }
                    Object value = entry.getValue();
                    return Collection.class.isInstance(value) ?
                            inOrIsNull(criteriaBuilder, key, Collection.class.cast(value)) :
                            equalOrIsNull(criteriaBuilder, key, value);
                })
                .toArray(Predicate[]::new);
        return criteriaBuilder.and(restrictions);
    }

    private Object getFieldValue(Object entity, String field) {
        try {
            return readField(entity, field, true);
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private Predicate equalOrIsNull(CriteriaBuilder cb, Expression<?> expression, Object value) {
        return value == null ? cb.isNull(expression) : cb.equal(expression, value);
    }

    private Predicate inOrIsNull(CriteriaBuilder criteriaBuilder, Expression<?> expression, Collection<?> values) {
        // A tiny optimization for common scenarios
        if (values.size() == 1) {
            return equalOrIsNull(criteriaBuilder, expression, values.iterator().next());
        }

        List<Predicate> predicates = new ArrayList<>();
        Collection<Object> nonNullValues = values.stream().filter(Objects::nonNull).collect(toList());
        if (nonNullValues.size() != values.size()) {
            predicates.add(criteriaBuilder.isNull(expression));
        }

        if (!nonNullValues.isEmpty()) {
            predicates.add(expression.in(nonNullValues));
        }

        // Zero predicates will always result to false
        return criteriaBuilder.or(predicates.toArray(new Predicate[0]));
    }
}
