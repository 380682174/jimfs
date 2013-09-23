/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.jimfs.internal;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.jimfs.Jimfs.CONFIG_KEY;
import static com.google.jimfs.Jimfs.URI_SCHEME;
import static com.google.jimfs.internal.LinkOptions.FOLLOW_LINKS;
import static com.google.jimfs.internal.LinkOptions.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.jimfs.Jimfs;
import com.google.jimfs.JimfsConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

/**
 * {@link FileSystemProvider} implementation for JIMFS. Should not be used directly. To create a
 * new file system instance, see {@link Jimfs}. For other operations, use the public APIs in
 * {@code java.nio.file}.
 *
 * @author Colin Decker
 */
public final class JimfsFileSystemProvider extends FileSystemProvider {

  @Override
  public String getScheme() {
    return URI_SCHEME;
  }

  private final ConcurrentMap<URI, JimfsFileSystem> fileSystems = new ConcurrentHashMap<>();

  @Override
  public JimfsFileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
    checkArgument(uri.getScheme().equalsIgnoreCase(URI_SCHEME),
        "uri (%s) scheme must be '%s'", uri, URI_SCHEME);
    checkArgument(isValidFileSystemUri(uri),
        "uri (%s) may not have a path, query or fragment", uri);
    checkArgument(env.get(CONFIG_KEY) instanceof JimfsConfiguration,
        "env map (%s) must contain key '%s' mapped to an instance of JimfsConfiguration",
        env, CONFIG_KEY);

    JimfsConfiguration config = (JimfsConfiguration) env.get(CONFIG_KEY);
    JimfsFileSystem fileSystem = FileSystemInitializer.createFileSystem(this, uri, config);
    if (fileSystems.putIfAbsent(uri, fileSystem) != null) {
      throw new FileSystemAlreadyExistsException(uri.toString());
    }
    return fileSystem;
  }

  @Override
  public JimfsFileSystem getFileSystem(URI uri) {
    JimfsFileSystem fileSystem = fileSystems.get(uri);
    if (fileSystem == null) {
      throw new FileSystemNotFoundException(uri.toString());
    }
    return fileSystem;
  }

  @Override
  public FileSystem newFileSystem(Path path, Map<String, ?> env) throws IOException {
    JimfsPath checkedPath = checkPath(path);
    checkNotNull(env);

    URI pathUri = checkedPath.toUri();
    URI jarUri = URI.create("jar:" + pathUri);

    try {
      // pass the new jar:jimfs://... URI to be handled by ZipFileSystemProvider
      return FileSystems.newFileSystem(jarUri, env);
    } catch (Exception e) {
      // if any exception occurred, assume the file wasn't a zip file and that we don't support
      // viewing it as a file system
      throw new UnsupportedOperationException(e);
    }
  }

  /**
   * Called when the given file system is closed to remove it from this provider.
   */
  void fileSystemClosed(JimfsFileSystem fileSystem) {
    fileSystems.remove(fileSystem.uri());
  }

  @Override
  public Path getPath(URI uri) {
    checkArgument(URI_SCHEME.equalsIgnoreCase(uri.getScheme()),
        "uri scheme does not match this provider: %s", uri);
    checkArgument(!isNullOrEmpty(uri.getPath()), "uri must have a path: %s", uri);

    return getFileSystem(toFileSystemUri(uri)).toPath(uri);
  }

  /**
   * Returns whether or not the given URI is valid as a base file system URI. It must not have a
   * path, query or fragment.
   */
  private boolean isValidFileSystemUri(URI uri) {
    // would like to just check null, but fragment appears to be the empty string when not present
    return isNullOrEmpty(uri.getPath())
        && isNullOrEmpty(uri.getQuery())
        && isNullOrEmpty(uri.getFragment());
  }

  /**
   * Returns the given URI with any path, query or fragment stripped off.
   */
  private URI toFileSystemUri(URI uri) {
    try {
      return new URI(
          uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(),
          null, null, null);
    } catch (URISyntaxException e) {
      throw new AssertionError(e);
    }
  }

  private static JimfsPath checkPath(Path path) {
    if (path instanceof JimfsPath) {
      return (JimfsPath) path;
    }
    throw new ProviderMismatchException(
        "path " + path + " is not associated with a JIMFS file system");
  }

  /**
   * Returns the file system service for the given path's file system.
   */
  private static FileSystemService getService(JimfsPath path) {
    return ((JimfsFileSystem) path.getFileSystem()).service();
  }

  private static LookupResult lookup(Path path, LinkOptions linkHandling) throws IOException {
    JimfsPath checkedPath = checkPath(path);
    FileSystemService service = getService(checkedPath);
    return service.lookup(checkedPath, linkHandling);
  }

  @Override
  public JimfsFileChannel newFileChannel(Path path, Set<? extends OpenOption> options,
      FileAttribute<?>... attrs) throws IOException {
    JimfsPath checkedPath = checkPath(path);
    OpenOptions opts = OpenOptions.from(getOptionsForChannel(options));
    File file = getService(checkedPath).getRegularFile(checkedPath, opts, attrs);
    return new JimfsFileChannel(file, opts);
  }

  @Override
  public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
      FileAttribute<?>... attrs) throws IOException {
    return newFileChannel(path, options, attrs);
  }

  @Override
  public AsynchronousFileChannel newAsynchronousFileChannel(Path path,
      Set<? extends OpenOption> options, ExecutorService executor, FileAttribute<?>... attrs)
      throws IOException {
    return newFileChannel(path, options, attrs).asAsynchronousFileChannel(executor);
  }

  @Override
  public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
    return newFileChannel(checkPath(path), getOptionsForRead(options)).asInputStream();
  }

  @Override
  public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
    return newFileChannel(checkPath(path), getOptionsForWrite(options)).asOutputStream();
  }

  static Set<? extends OpenOption> getOptionsForChannel(Set<? extends OpenOption> options) {
    if (!options.contains(READ) && !options.contains(WRITE)) {
      OpenOption optionToAdd = options.contains(APPEND) ? WRITE : READ;
      return ImmutableSet.<OpenOption>builder()
          .addAll(options)
          .add(optionToAdd)
          .build();
    }
    return options;
  }

  private static Set<OpenOption> getOptionsForRead(OpenOption... options) {
    Set<OpenOption> optionsSet = new HashSet<>(Arrays.asList(options));
    if (optionsSet.contains(WRITE)) {
      throw new UnsupportedOperationException("WRITE");
    } else {
      optionsSet.add(READ);
    }
    return optionsSet;
  }

  private static Set<OpenOption> getOptionsForWrite(OpenOption... options) {
    Set<OpenOption> optionsSet = Sets.newHashSet(options);
    if (optionsSet.contains(READ)) {
      throw new UnsupportedOperationException("READ");
    } else if (optionsSet.isEmpty()) {
      optionsSet.addAll(Arrays.asList(CREATE, WRITE, TRUNCATE_EXISTING));
    } else if (!optionsSet.contains(WRITE)) {
      optionsSet.add(WRITE);
    }
    return optionsSet;
  }

  @Override
  public DirectoryStream<Path> newDirectoryStream(Path dir,
      DirectoryStream.Filter<? super Path> filter) throws IOException {
    JimfsPath checkedPath = checkPath(dir);
    return getService(checkedPath)
        .newSecureDirectoryStream(checkedPath, filter, LinkOptions.FOLLOW_LINKS, checkedPath);
  }

  @Override
  public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
    JimfsPath checkedPath = checkPath(dir);
    FileSystemService service = getService(checkedPath);
    service.createDirectory(checkedPath, attrs);
  }

  @Override
  public void createLink(Path link, Path existing) throws IOException {
    JimfsPath linkPath = checkPath(link);
    JimfsPath existingPath = checkPath(existing);
    checkArgument(linkPath.getFileSystem().equals(existingPath.getFileSystem()),
        "link and existing paths must belong to the same file system instance");
    FileSystemService service = getService(linkPath);
    service.link(linkPath, getService(existingPath), existingPath);
  }

  @Override
  public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs)
      throws IOException {
    JimfsPath linkPath = checkPath(link);
    JimfsPath targetPath = checkPath(target);
    checkArgument(linkPath.getFileSystem().equals(targetPath.getFileSystem()),
        "link and target paths must belong to the same file system instance");
    FileSystemService service = getService(linkPath);
    service.createSymbolicLink(linkPath, targetPath, attrs);
  }

  @Override
  public Path readSymbolicLink(Path link) throws IOException {
    File file = lookup(link, NOFOLLOW_LINKS)
        .requireSymbolicLink(link)
        .file();

    return file.<JimfsPath>content();
  }

  @Override
  public void delete(Path path) throws IOException {
    JimfsPath checkedPath = checkPath(path);
    FileSystemService service = getService(checkedPath);
    service.deleteFile(checkedPath);
  }

  @Override
  public void copy(Path source, Path target, CopyOption... options) throws IOException {
    copy(source, target, CopyOptions.copy(options));
  }

  @Override
  public void move(Path source, Path target, CopyOption... options) throws IOException {
    copy(source, target, CopyOptions.move(options));
  }

  private void copy(Path source, Path target, CopyOptions options) throws IOException {
    JimfsPath sourcePath = checkPath(source);
    JimfsPath targetPath = checkPath(target);

    FileSystemService sourceService = getService(sourcePath);
    FileSystemService targetService = getService(targetPath);
    sourceService.copy(sourcePath, targetService, targetPath, options);
  }

  @Override
  public boolean isSameFile(Path path, Path path2) throws IOException {
    if (path.equals(path2)) {
      return true;
    }

    if (!(path instanceof JimfsPath && path2 instanceof JimfsPath)) {
      return false;
    }

    JimfsPath checkedPath = (JimfsPath) path;
    JimfsPath checkedPath2 = (JimfsPath) path2;

    FileSystemService service = getService(checkedPath);
    FileSystemService service2 = getService(checkedPath2);

    return service.isSameFile(checkedPath, service2, checkedPath2);
  }

  @Override
  public boolean isHidden(Path path) throws IOException {
    // TODO(cgdecker): This should probably be configurable, but this seems fine for now
    /*
     * If the DOS view is supported, use the Windows isHidden method (check the dos:hidden
     * attribute). Otherwise, use the Unix isHidden method (just check if the file name starts with
     * ".").
     */
    JimfsPath checkedPath = checkPath(path);
    FileSystemService service = getService(checkedPath);
    JimfsFileStore store = service.fileStore();
    if (store.supportsFileAttributeView("dos")) {
      return service.readAttributes(checkedPath, DosFileAttributes.class, NOFOLLOW_LINKS)
          .isHidden();
    }
    return path.getNameCount() > 0 && path.getFileName().toString().startsWith(".");
  }

  @Override
  public FileStore getFileStore(Path path) throws IOException {
    // only one FileStore per file system
    return Iterables.getOnlyElement(checkPath(path).getFileSystem().getFileStores());
  }

  @Override
  public void checkAccess(Path path, AccessMode... modes) throws IOException {
    JimfsPath checkedPath = checkPath(path);
    lookup(checkedPath, FOLLOW_LINKS).requireFound(path);
  }

  @Override
  public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type,
      LinkOption... options) {
    JimfsPath checkedPath = checkPath(path);
    return getService(checkedPath)
        .getFileAttributeView(checkedPath, type, LinkOptions.from(options));
  }

  @Override
  public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type,
      LinkOption... options) throws IOException {
    JimfsPath checkedPath = checkPath(path);
    return getService(checkedPath)
        .readAttributes(checkedPath, type, LinkOptions.from(options));
  }

  @Override
  public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
      throws IOException {
    JimfsPath checkedPath = checkPath(path);
    return getService(checkedPath)
        .readAttributes(checkedPath, attributes, LinkOptions.from(options));
  }

  @Override
  public void setAttribute(Path path, String attribute, Object value, LinkOption... options)
      throws IOException {
    JimfsPath checkedPath = checkPath(path);
    getService(checkedPath)
        .setAttribute(checkedPath, attribute, value, LinkOptions.from(options));
  }
}