package com.google.copybara.transform;

import static com.google.copybara.testing.FileSubjects.assertThatPath;

import com.google.common.jimfs.Jimfs;
import com.google.copybara.ConfigValidationException;
import com.google.copybara.Core;
import com.google.copybara.TransformWork;
import com.google.copybara.ValidationException;
import com.google.copybara.testing.OptionsBuilder;
import com.google.copybara.testing.SkylarkTestExecutor;
import com.google.copybara.util.console.testing.TestingConsole;
import com.google.copybara.util.console.testing.TestingConsole.MessageType;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class MoveTest {

  private OptionsBuilder options;
  private Path checkoutDir;
  private TestingConsole console;
  private SkylarkTestExecutor skylark;

  @Rule
  public final ExpectedException thrown = ExpectedException.none();

  @Before
  public void setup() throws IOException {
    FileSystem fs = Jimfs.newFileSystem();
    checkoutDir = fs.getPath("/test-checkoutDir");
    Files.createDirectories(checkoutDir);
    console = new TestingConsole();
    options = new OptionsBuilder()
        .setConsole(console);
    skylark = new SkylarkTestExecutor(options, Core.class, Move.class);
  }

  private void transform(Move mover) throws IOException, ValidationException {
    mover.transform(new TransformWork(checkoutDir, "testmsg"), console);
  }

  @Test
  public void testMoveAndItsReverse() throws Exception {
    Move mover = skylark.eval("m", ""
        + "m = core.move(before = 'one.before', after = 'folder/one.after'\n"
        + ")");
    Files.write(checkoutDir.resolve("one.before"), new byte[]{});
    transform(mover);

    assertThatPath(checkoutDir)
        .containsFiles("folder/one.after")
        .containsNoMoreFiles();

    transform(mover.reverse());

    assertThatPath(checkoutDir)
        .containsFiles("one.before")
        .containsNoMoreFiles();
  }

  @Test
  public void testDoesntExist() throws Exception {
    Move mover = skylark.eval("m", ""
        + "m = core.move(before = 'blablabla', after = 'other')\n");
    thrown.expect(ValidationException.class);
    thrown.expectMessage("Error moving 'blablabla'. It doesn't exist");
    transform(mover);
  }

  @Test
  public void testDoesntExistAsWarning() throws Exception {
    options.workflowOptions.ignoreNoop = true;

    Move mover = skylark.eval("m", ""
        + "m = core.move(before = 'blablabla', after = 'other')\n");

    transform(mover);

    console.assertThat()
        .onceInLog(MessageType.WARNING, ".*blablabla.*doesn't exist.*");
  }

  @Test
  public void testAbsoluteBefore() throws Exception {
    skylark.evalFails(
        "core.move(before = '/blablabla', after = 'other')\n",
        "path must be relative.*/blablabla");
  }

  @Test
  public void testAbsoluteAfter() throws Exception {
    skylark.evalFails(
        "core.move(after = '/blablabla', before = 'other')\n",
        "path must be relative.*/blablabla");
  }

  @Test
  public void testDotDot() throws Exception {
    skylark.evalFails(
        "core.move(after = '../blablabla', before = 'other')\n",
        "path has unexpected [.] or [.][.] components.*[.][.]/blablabla");
  }

  @Test
  public void testDestinationExist() throws Exception {
    Files.write(checkoutDir.resolve("one"), new byte[]{});
    Files.write(checkoutDir.resolve("two"), new byte[]{});
    Move mover = skylark.eval("m", "m = core.move(before = 'one', after = 'two')\n");
    thrown.expect(ValidationException.class);
    thrown.expectMessage("Cannot move file to '/test-checkoutDir/two' because it already exists");
    transform(mover);
  }

  @Test
  public void testDestinationExistDirectory() throws Exception {
    Files.createDirectories(checkoutDir.resolve("folder"));
    Files.write(checkoutDir.resolve("one"), new byte[]{});
    Move mover = skylark.eval("m", "m = core.move(before = 'one', after = 'folder/two')\n");
    transform(mover);

    assertThatPath(checkoutDir)
        .containsFiles("folder/two")
        .containsNoMoreFiles();
  }

  @Test
  public void testMoveToCheckoutDirRoot() throws Exception {
    Move mover = skylark.eval("m",
        "m = core.move(before = 'third_party/java', after = '')\n");
    Files.createDirectories(checkoutDir.resolve("third_party/java/org"));
    Files.write(checkoutDir.resolve("third_party/java/one.java"), new byte[]{});
    Files.write(checkoutDir.resolve("third_party/java/org/two.java"), new byte[]{});

    transform(mover);

    assertThatPath(checkoutDir)
        .containsFiles("one.java", "org/two.java")
        .containsNoMoreFiles();
  }

  /**
   * In bash if foo/ exist, the following command:
   *
   * <pre>
   *   mv third_party/java foo/
   * </pre>
   *
   * would create foo/java instead of moving the content of java to foo. Sadly, this behavior would
   * make Move non-reversible. As it would need to know if foo exist or not for computing the
   * reverse. This test ensures that Move doesn't work like that but instead puts the content
   * of java in foo, no matter that foo exist or not.
   */
  @Test
  public void testMoveDir() throws Exception {
    Move mover = skylark.eval("m",
        "m = core.move(before = 'third_party/java', after = 'foo')\n");
    Files.createDirectories(checkoutDir.resolve("third_party/java/org"));
    Files.createDirectories(checkoutDir.resolve("foo"));
    Files.write(checkoutDir.resolve("third_party/java/one.java"), new byte[]{});
    Files.write(checkoutDir.resolve("third_party/java/org/two.java"), new byte[]{});

    transform(mover);

    assertThatPath(checkoutDir)
        .containsFiles("foo/one.java", "foo/org/two.java")
        .containsNoMoreFiles();
  }

  @Test
  public void testMoveFromCheckoutDirRootToSubdir() throws Exception {
    Move mover = skylark.eval("m",
        "m = core.move(before = '', after = 'third_party/java')\n");
    Files.write(checkoutDir.resolve("file.java"), new byte[]{});
    transform(mover);

    assertThatPath(checkoutDir)
        .containsFiles("third_party/java/file.java")
        .containsNoMoreFiles();
  }

  @Test
  public void testCannotMoveFromRootToAlreadyExistingDir() throws Exception {
    Move mover = skylark.eval("m",
        "m = core.move(before = '', after = 'third_party/java')\n");
    Files.createDirectories(checkoutDir.resolve("third_party/java"));
    Files.write(checkoutDir.resolve("third_party/java/bar.java"), new byte[]{});
    Files.write(checkoutDir.resolve("third_party/java/foo.java"), new byte[]{});

    thrown.expect(ValidationException.class);
    thrown.expectMessage(
        "Files already exist in " + checkoutDir + "/third_party/java: [bar.java, foo.java]");
    transform(mover);
  }

  @Test
  public void errorForMissingBefore() throws Exception {
    try {
      skylark.<Move>eval("m", "m = core.move(after = 'third_party/java')\n");
      Assert.fail();
    } catch (ConfigValidationException expected) {}

    console.assertThat()
        .onceInLog(MessageType.ERROR, ".*missing mandatory .* 'before'.*");
  }

  @Test
  public void errorForMissingAfter() throws Exception {
    try {
      skylark.<Move>eval("m", "m = core.move(before = 'third_party/java')\n");
      Assert.fail();
    } catch (ConfigValidationException expected) {}

    console.assertThat()
        .onceInLog(MessageType.ERROR, ".*missing mandatory .* 'after'.*");
  }
}