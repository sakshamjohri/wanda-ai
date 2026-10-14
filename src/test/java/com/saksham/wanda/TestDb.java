package com.saksham.wanda;

import com.saksham.wanda.db.Database;
import com.saksham.wanda.db.UserDao;
import com.saksham.wanda.model.User;

import java.util.concurrent.atomic.AtomicInteger;

/** Shared in-memory database for the tests (created once per test run). */
public final class TestDb {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private TestDb() { }

    public static Database db() {
        return Database.init("jdbc:h2:mem:wanda_test;DB_CLOSE_DELAY=-1");
    }

    /** A fresh user with a unique name, so tests never see each other's data. */
    public static User newUser() {
        String name = "user" + COUNTER.incrementAndGet() + "_" + System.nanoTime();
        return new UserDao(db()).save(new User(name, name, "x"));
    }
}
