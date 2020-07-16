package tech.tablesaw.io.saw;

import static tech.tablesaw.io.saw.SawUtils.BOOLEAN;
import static tech.tablesaw.io.saw.SawUtils.DOUBLE;
import static tech.tablesaw.io.saw.SawUtils.FLOAT;
import static tech.tablesaw.io.saw.SawUtils.INSTANT;
import static tech.tablesaw.io.saw.SawUtils.INTEGER;
import static tech.tablesaw.io.saw.SawUtils.LOCAL_DATE;
import static tech.tablesaw.io.saw.SawUtils.LOCAL_DATE_TIME;
import static tech.tablesaw.io.saw.SawUtils.LOCAL_TIME;
import static tech.tablesaw.io.saw.SawUtils.LONG;
import static tech.tablesaw.io.saw.SawUtils.SHORT;
import static tech.tablesaw.io.saw.SawUtils.STRING;
import static tech.tablesaw.io.saw.SawUtils.TEXT;
import static tech.tablesaw.io.saw.TableMetadata.METADATA_FILE_NAME;

import com.google.common.base.Preconditions;
import it.unimi.dsi.fastutil.bytes.Byte2IntMap;
import it.unimi.dsi.fastutil.bytes.Byte2ObjectMap;
import it.unimi.dsi.fastutil.bytes.ByteIterator;
import it.unimi.dsi.fastutil.floats.FloatIterator;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.shorts.Short2IntMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import it.unimi.dsi.fastutil.shorts.ShortIterator;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.iq80.snappy.SnappyFramedOutputStream;
import tech.tablesaw.api.BooleanColumn;
import tech.tablesaw.api.DateColumn;
import tech.tablesaw.api.DateTimeColumn;
import tech.tablesaw.api.DoubleColumn;
import tech.tablesaw.api.FloatColumn;
import tech.tablesaw.api.InstantColumn;
import tech.tablesaw.api.IntColumn;
import tech.tablesaw.api.LongColumn;
import tech.tablesaw.api.ShortColumn;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.TextColumn;
import tech.tablesaw.api.TimeColumn;
import tech.tablesaw.columns.Column;
import tech.tablesaw.columns.strings.ByteDictionaryMap;
import tech.tablesaw.columns.strings.DictionaryMap;
import tech.tablesaw.columns.strings.IntDictionaryMap;
import tech.tablesaw.columns.strings.ShortDictionaryMap;
import tech.tablesaw.table.Relation;

@SuppressWarnings("WeakerAccess")
public class SawWriter {

  private static final int FLUSH_AFTER_ITERATIONS = 20_000;

  private static final int WRITER_POOL_SIZE = 10;

  /**
   * Saves the data from the given table in the location specified by parentFolderName. Within that
   * folder each table has its own sub-folder, whose name is based on the name of the table.
   *
   * <p>NOTE: If you store a table with the same name in the same folder. The data in that folder
   * will be over-written.
   *
   * <p>The storage format is the tablesaw compressed column-oriented format, which consists of a
   * set of file in a folder. The name of the folder is based on the name of the table.
   *
   * @param parentFolderName The location of the table (for example: "mytables")
   * @param table The table to be saved
   * @return The path and name of the table
   * @throws UncheckedIOException wrapping IOException if the file can not be read
   */
  public static String saveTable(String parentFolderName, Relation table) {
    return saveTable(parentFolderName, table, WRITER_POOL_SIZE);
  }

  /**
   * Saves the data from the given table in the location specified by parentFolderName. Within that
   * folder each table has its own sub-folder, whose name is based on the name of the table.
   *
   * <p>NOTE: If you store a table with the same name in the same folder. The data in that folder
   * will be over-written.
   *
   * <p>The storage format is the tablesaw compressed column-oriented format, which consists of a
   * set of file in a folder. The name of the folder is based on the name of the table.
   *
   * @param parentFolderName The location of the table (for example: "mytables")
   * @param table The table to be saved
   * @param threadPoolSize The size of the the thread-pool allocated to writing. Each column is
   *     written in own thread
   * @return The path and name of the table
   * @throws UncheckedIOException wrapping IOException if the file can not be read
   */
  public static String saveTable(String parentFolderName, Relation table, int threadPoolSize) {

    Preconditions.checkArgument(
        parentFolderName != null, "The folder name for the saw output cannot be null");
    Preconditions.checkArgument(
        !parentFolderName.isEmpty(), "The folder name for the saw output cannot be empty");

    ExecutorService executorService = Executors.newFixedThreadPool(threadPoolSize);
    CompletionService<Void> writerCompletionService =
        new ExecutorCompletionService<>(executorService);

    // creates the containing foler
    Path folderPath = Paths.get(parentFolderName);

    if (!Files.exists(folderPath)) {
      try {
        Files.createDirectories(folderPath);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    // creates the folder containing the files
    String sawFolderName = SawUtils.makeName(table.name());
    Path filePath = folderPath.resolve(sawFolderName);

    if (Files.exists(filePath)) {
      try (Stream<Path> stream = Files.walk(filePath)) {
        stream
            .map(Path::toFile)
            .sorted((o1, o2) -> Comparator.<File>reverseOrder().compare(o1, o2))
            .forEach(File::delete);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    try {
      Files.createDirectories(filePath);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    try {
      TableMetadata tableMetadata = new TableMetadata(table);

      Path metaDataPath = filePath.resolve(METADATA_FILE_NAME);

      writeTableMetadata(metaDataPath, tableMetadata);

      List<Column<?>> columns = table.columns();
      for (int i = 0; i < columns.size(); i++) {
        Column<?> column = columns.get(i);
        String pathString = tableMetadata.getColumnMetadataList().get(i).getId();

        writerCompletionService.submit(
            () -> {
              Path columnPath = filePath.resolve(pathString);
              writeColumn(columnPath.toString(), column);
              return null;
            });
      }
      for (int i = 0; i < table.columnCount(); i++) {
        Future<Void> future = writerCompletionService.take();
        future.get();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException e) {
      throw new IllegalStateException(e);
    } finally {
      executorService.shutdown();
    }
    return filePath.toAbsolutePath().toString();
  }

  private static void writeColumn(String fileName, Column<?> column) {
    try {
      final String typeName = column.type().name();
      switch (typeName) {
        case FLOAT:
          writeColumn(fileName, (FloatColumn) column);
          break;
        case DOUBLE:
          writeColumn(fileName, (DoubleColumn) column);
          break;
        case INTEGER:
          writeColumn(fileName, (IntColumn) column);
          break;
        case BOOLEAN:
          writeColumn(fileName, (BooleanColumn) column);
          break;
        case LOCAL_DATE:
          writeColumn(fileName, (DateColumn) column);
          break;
        case LOCAL_TIME:
          writeColumn(fileName, (TimeColumn) column);
          break;
        case LOCAL_DATE_TIME:
          writeColumn(fileName, (DateTimeColumn) column);
          break;
        case STRING:
          writeColumn(fileName, (StringColumn) column);
          break;
        case TEXT:
          writeColumn(fileName, (TextColumn) column);
          break;
        case INSTANT:
          writeColumn(fileName, (InstantColumn) column);
          break;
        case SHORT:
          writeColumn(fileName, (ShortColumn) column);
          break;
        case LONG:
          writeColumn(fileName, (LongColumn) column);
          break;
        default:
          throw new IllegalArgumentException("Unhandled column type writing columns");
      }
    } catch (IOException e) {
      throw new UncheckedIOException("IOException writing to file", e);
    }
  }

  private static void writeColumn(String fileName, FloatColumn column) throws IOException {
    try (FileOutputStream fos = new FileOutputStream(fileName);
        SnappyFramedOutputStream sos = new SnappyFramedOutputStream(fos);
        DataOutputStream dos = new DataOutputStream(sos)) {
      int i = 0;
      FloatIterator iterator = (FloatIterator) column.iterator();
      while (iterator.hasNext()) {
        dos.writeFloat(iterator.nextFloat());
        i++;
        if (i == FLUSH_AFTER_ITERATIONS) {
          dos.flush();
          i = 0;
        }
      }
      dos.flush();
    }
  }

  private static void writeColumn(String fileName, DoubleColumn column) throws IOException {
    try (FileOutputStream fos = new FileOutputStream(fileName);
        SnappyFramedOutputStream sos = new SnappyFramedOutputStream(fos);
        DataOutputStream dos = new DataOutputStream(sos)) {
      int i = 0;
      for (double d : column) {
        dos.writeDouble(d);
        i++;
        if (i == FLUSH_AFTER_ITERATIONS) {
          dos.flush();
          i = 0;
        }
      }
      dos.flush();
    }
  }

  /**
   * Writes out the values of the String column encoded as ints to minimize the time required for
   * subsequent reads
   *
   * <p>The files are written Strings first, then the ints that encode them so they can be read in
   * the opposite order
   *
   * @throws IOException IOException if the file can not be read
   */
  private static void writeColumn(String fileName, StringColumn column) throws IOException {
    try (FileOutputStream fos = new FileOutputStream(fileName);
        SnappyFramedOutputStream sos = new SnappyFramedOutputStream(fos);
        DataOutputStream dos = new DataOutputStream(sos)) {

      // write the strings

      DictionaryMap lookupTable = column.getDictionary();
      if (lookupTable.getClass().equals(ByteDictionaryMap.class)) {
        writeToStream((ByteDictionaryMap) lookupTable, dos);
      } else if (lookupTable.getClass().equals(ShortDictionaryMap.class)) {
        writeToStream((ShortDictionaryMap) lookupTable, dos);
      } else {
        writeToStream((IntDictionaryMap) lookupTable, dos);
      }
    }
  }

  /**
   * Writes the contents of the dictionaryMap to a stream in saw file format
   *
   * @param dos The stream to write on
   */
  private static void writeToStream(ByteDictionaryMap dictionary, DataOutputStream dos) {

    try {
      // write the maps
      // we write each one keys first, then values
      // the idea is that there are relatively few unique values so it's cheap to write them more
      // than once
      ObjectSet<Byte2ObjectMap.Entry<String>> entries = dictionary.getKeyValueEntries();
      for (Byte2ObjectMap.Entry<String> entry : entries) {
        dos.writeByte(entry.getByteKey());
      }

      for (Byte2ObjectMap.Entry<String> entry : entries) {
        dos.writeUTF(entry.getValue());
      }

      ObjectSet<Byte2IntMap.Entry> counts = dictionary.getKeyCountEntries();

      for (Byte2IntMap.Entry count : counts) {
        dos.writeByte(count.getByteKey());
      }

      for (Byte2IntMap.Entry count : counts) {
        dos.writeInt(count.getIntValue());
      }

      // write the values in column order, including repeats
      for (byte d : dictionary.values()) {
        dos.writeByte(d);
      }
      dos.flush();
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  /**
   * Writes the contents of the dictionaryMap to a stream in saw file format
   *
   * @param dos The stream to write on
   */
  private static void writeToStream(ShortDictionaryMap dictionary, DataOutputStream dos) {

    try {
      // write the maps
      // we write each one keys first, then values
      // the idea is that there are relatively few unique values so it's cheap to write them more
      // than once
      ObjectSet<Short2ObjectMap.Entry<String>> entries = dictionary.getKeyValueEntries();

      for (Short2ObjectMap.Entry<String> entry : entries) {
        dos.writeShort(entry.getShortKey());
      }

      for (Short2ObjectMap.Entry<String> entry : entries) {
        dos.writeUTF(entry.getValue());
      }

      ObjectSet<Short2IntMap.Entry> counts = dictionary.getKeyCountEntries();

      for (Short2IntMap.Entry count : counts) {
        dos.writeShort(count.getShortKey());
      }

      for (Short2IntMap.Entry count : counts) {
        dos.writeInt(count.getIntValue());
      }

      // write the values in column order, including repeats
      for (short d : dictionary.values()) {
        dos.writeShort(d);
      }
      dos.flush();
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  /**
   * Writes the contents of the dictionaryMap to a stream in saw file format
   *
   * @param dos The stream to write on
   */
  private static void writeToStream(IntDictionaryMap dictionary, DataOutputStream dos) {

    try {
      // write the maps
      // we write each one keys first, then values
      // the idea is that there are relatively few unique values so it's cheap to write them more
      // than once
      ObjectSet<Int2ObjectMap.Entry<String>> entries = dictionary.getKeyValueEntries();

      for (Int2ObjectMap.Entry<String> entry : entries) {
        dos.writeInt(entry.getIntKey());
      }

      for (Int2ObjectMap.Entry<String> entry : entries) {
        dos.writeUTF(entry.getValue());
      }

      ObjectSet<Int2IntMap.Entry> counts = dictionary.getKeyCountEntries();

      for (Int2IntMap.Entry count : counts) {
        dos.writeInt(count.getIntKey());
      }

      for (Int2IntMap.Entry count : counts) {
        dos.writeInt(count.getIntValue());
      }

      // write the values in column order, including repeats
      for (int d : dictionary.values()) {
        dos.writeInt(d);
      }
      dos.flush();
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  /**
   * Writes out the values of the TextColumn
   *
   * <p>
   *
   * @throws IOException IOException if the file can not be written
   */
  private static void writeColumn(String fileName, TextColumn column) throws IOException {

    try (FileOutputStream fos = new FileOutputStream(fileName);
        SnappyFramedOutputStream sos = new SnappyFramedOutputStream(fos);
        DataOutputStream dos = new DataOutputStream(sos)) {

      for (String str : column) {
        dos.writeUTF(str);
      }
      dos.flush();
    }
  }

  // TODO(lwhite): saveTable the column using integer compression
  private static void writeColumn(String fileName, IntColumn column) throws IOException {
    try (FileOutputStream fos = new FileOutputStream(fileName);
        SnappyFramedOutputStream sos = new SnappyFramedOutputStream(fos);
        DataOutputStream dos = new DataOutputStream(sos)) {
      int i = 0;
      IntIterator iterator = (IntIterator) column.iterator();
      while (iterator.hasNext()) {
        dos.writeInt(iterator.nextInt());
        i++;
        if (i == FLUSH_AFTER_ITERATIONS) { // TODO does this break the pipelining?
          dos.flush();
          i = 0;
        }
      }
      dos.flush();
    }
  }

  private static void writeColumn(String fileName, ShortColumn column) throws IOException {
    try (FileOutputStream fos = new FileOutputStream(fileName);
        SnappyFramedOutputStream sos = new SnappyFramedOutputStream(fos);
        DataOutputStream dos = new DataOutputStream(sos)) {
      int i = 0;
      ShortIterator iterator = (ShortIterator) column.iterator();
      while (iterator.hasNext()) {
        dos.writeShort(iterator.nextShort());
        i++;
        if (i == FLUSH_AFTER_ITERATIONS) {
          dos.flush();
          i = 0;
        }
      }
      dos.flush();
    }
  }

  private static void writeColumn(String fileName, LongColumn column) throws IOException {
    try (FileOutputStream fos = new FileOutputStream(fileName);
        SnappyFramedOutputStream sos = new SnappyFramedOutputStream(fos);
        DataOutputStream dos = new DataOutputStream(sos)) {
      int i = 0;
      LongIterator iterator = (LongIterator) column.iterator();
      while (iterator.hasNext()) {
        dos.writeLong(iterator.nextLong());
        i++;
        if (i == FLUSH_AFTER_ITERATIONS) {
          dos.flush();
          i = 0;
        }
      }
      dos.flush();
    }
  }

  // TODO(lwhite): saveTable the column using integer compression?
  private static void writeColumn(String fileName, DateColumn column) throws IOException {
    try (FileOutputStream fos = new FileOutputStream(fileName);
        SnappyFramedOutputStream sos = new SnappyFramedOutputStream(fos);
        DataOutputStream dos = new DataOutputStream(sos)) {
      int i = 0;
      IntIterator iterator = column.intIterator();
      while (iterator.hasNext()) {
        dos.writeInt(iterator.nextInt());
        i++;
        if (i == FLUSH_AFTER_ITERATIONS) {
          dos.flush();
          i = 0;
        }
      }
      dos.flush();
    }
  }

  private static void writeColumn(String fileName, DateTimeColumn column) throws IOException {
    try (FileOutputStream fos = new FileOutputStream(fileName);
        SnappyFramedOutputStream sos = new SnappyFramedOutputStream(fos);
        DataOutputStream dos = new DataOutputStream(sos)) {
      int i = 0;
      LongIterator iterator = column.longIterator();
      while (iterator.hasNext()) {
        dos.writeLong(iterator.nextLong());
        i++;
        if (i == FLUSH_AFTER_ITERATIONS) {
          dos.flush();
          i = 0;
        }
      }
      dos.flush();
    }
  }

  private static void writeColumn(String fileName, InstantColumn column) throws IOException {
    try (FileOutputStream fos = new FileOutputStream(fileName);
        SnappyFramedOutputStream sos = new SnappyFramedOutputStream(fos);
        DataOutputStream dos = new DataOutputStream(sos)) {
      int i = 0;
      LongIterator iterator = column.longIterator();
      while (iterator.hasNext()) {
        dos.writeLong(iterator.nextLong());
        i++;
        if (i == FLUSH_AFTER_ITERATIONS) {
          dos.flush();
          i = 0;
        }
      }
      dos.flush();
    }
  }

  // TODO(lwhite): save the column using integer compression?
  private static void writeColumn(String fileName, TimeColumn column) throws IOException {
    try (FileOutputStream fos = new FileOutputStream(fileName);
        SnappyFramedOutputStream sos = new SnappyFramedOutputStream(fos);
        DataOutputStream dos = new DataOutputStream(sos)) {
      int i = 0;
      IntIterator iterator = column.intIterator();
      while (iterator.hasNext()) {
        dos.writeInt(iterator.nextInt());
        i++;
        if (i == FLUSH_AFTER_ITERATIONS) {
          dos.flush();
          i = 0;
        }
      }
      dos.flush();
    }
  }

  // TODO(lwhite): save the column using compressed bitmap?
  private static void writeColumn(String fileName, BooleanColumn column) throws IOException {
    try (FileOutputStream fos = new FileOutputStream(fileName);
        SnappyFramedOutputStream sos = new SnappyFramedOutputStream(fos);
        DataOutputStream dos = new DataOutputStream(sos)) {
      int i = 0;
      ByteIterator iterator = column.byteIterator();
      while (iterator.hasNext()) {
        dos.writeByte(iterator.nextByte());
        i++;
        if (i == FLUSH_AFTER_ITERATIONS) {
          dos.flush();
          i = 0;
        }
      }
      dos.flush();
    }
  }

  /**
   * Writes out a json-formatted representation of the given {@code table}'s metadata to the given
   * {@code file}
   *
   * @param filePath The full file path including file name
   * @throws IOException if the file can not be read
   */
  private static void writeTableMetadata(Path filePath, TableMetadata metadata) throws IOException {
    try {
      Files.createFile(filePath);
    } catch (FileAlreadyExistsException e) {
      // do nothing. overwrite existing file
    }
    try (FileOutputStream fOut = new FileOutputStream(filePath.toFile());
        OutputStreamWriter myOutWriter = new OutputStreamWriter(fOut)) {
      String output = metadata.toJson();
      myOutWriter.append(output);
    }
  }
}
