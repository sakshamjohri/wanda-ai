package com.saksham.wanda.db;

import java.util.List;
import java.util.Optional;

/**
 * Generic CRUD contract shared by every entity DAO.
 *
 * @param <T> entity type handled by the implementation
 */
public interface Dao<T> {

    Optional<T> findById(long id);

    /** All rows owned by a parent record (a user or a conversation). */
    List<T> findByOwner(long ownerId);

    /** Inserts the entity, sets its generated id and returns it. */
    T save(T entity);

    boolean delete(long id);
}
