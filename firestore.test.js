const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: cannot read or write folders", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("folders").get());
});

test("Authenticated user: can create and list their own folders", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("folders").doc("folder_1").set({
      userId: ALICE_UID,
      name: "NID Cards",
      parentId: "",
      sortOrder: 0,
      createdAtMs: 1700000000000,
      updatedAtMs: 1700000000000,
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("folders").get()
  );
});

test("Authenticated user: cannot read or write another user's folders", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date();
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("folders").doc("folder_1").set({
      userId: ALICE_UID,
      name: "Private Docs",
      parentId: "",
      sortOrder: 0,
      createdAtMs: 1700000000000,
      updatedAtMs: 1700000000000,
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("folders").doc("folder_1").get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("folders").get()
  );
});

test("Shadow update with extra field is rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("folders").doc("folder_1").set({
      userId: ALICE_UID,
      name: "Docs",
      parentId: "",
      sortOrder: 0,
      createdAtMs: 1700000000000,
      updatedAtMs: 1700000000000,
      createdAt: now,
      updatedAt: now,
      isAdmin: true,
    })
  );
});
