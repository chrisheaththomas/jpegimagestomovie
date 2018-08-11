package com.sun.samples;

import java.awt.Dimension;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Vector;

import javax.media.Buffer;
import javax.media.ConfigureCompleteEvent;
import javax.media.ControllerEvent;
import javax.media.ControllerListener;
import javax.media.DataSink;
import javax.media.EndOfMediaEvent;
import javax.media.Format;
import javax.media.Manager;
import javax.media.MediaLocator;
import javax.media.PrefetchCompleteEvent;
import javax.media.Processor;
import javax.media.RealizeCompleteEvent;
import javax.media.ResourceUnavailableEvent;
import javax.media.Time;
import javax.media.control.TrackControl;
import javax.media.datasink.DataSinkErrorEvent;
import javax.media.datasink.DataSinkEvent;
import javax.media.datasink.DataSinkListener;
import javax.media.datasink.EndOfStreamEvent;
import javax.media.format.VideoFormat;
import javax.media.protocol.ContentDescriptor;
import javax.media.protocol.DataSource;
import javax.media.protocol.FileTypeDescriptor;
import javax.media.protocol.PullBufferDataSource;
import javax.media.protocol.PullBufferStream;

/** This program takes a list of JPEG image files and convert them into a QuickTime movie. */
public class JpegImagesToMovie implements ControllerListener, DataSinkListener {

  private boolean doIt(
      int width, int height, int frameRate, List<byte[]> frames, MediaLocator outML) {
    ImageDataSource ids = new ImageDataSource(width, height, frameRate, new Vector<>(frames));

    Processor processor;

    try {
      // System.err
      // .println("- create processor for the image datasource ...");
      processor = Manager.createProcessor(ids);
    } catch (Exception e) {
      System.err.println("Yikes!  Cannot create a processor from the data source.");
      return false;
    }

    processor.addControllerListener(this);

    // Put the Processor into configured state so we can set
    // some processing options on the processor.
    processor.configure();
    if (isTransitioning(processor, processor.Configured)) {
      System.err.println("Failed to configure the processor.");
      return false;
    }

    // Set the output content descriptor to QuickTime.
    processor.setContentDescriptor(new ContentDescriptor(FileTypeDescriptor.QUICKTIME));

    // Query for the processor for supported formats.
    // Then set it on the processor.
    TrackControl tcs[] = processor.getTrackControls();
    Format f[] = tcs[0].getSupportedFormats();
    if (f == null || f.length <= 0) {
      System.err.println("The mux does not support the input format: " + tcs[0].getFormat());
      return false;
    }

    tcs[0].setFormat(f[0]);

    // System.err.println("Setting the track format to: " + f[0]);

    // We are done with programming the processor. Let's just
    // realize it.
    processor.realize();
    if (isTransitioning(processor, processor.Realized)) {
      System.err.println("Failed to realize the processor.");
      return false;
    }

    // Now, we'll need to create a DataSink.
    DataSink dsink;
    if ((dsink = createDataSink(processor, outML)) == null) {
      System.err.println("Failed to create a DataSink for the given output MediaLocator: " + outML);
      return false;
    }

    dsink.addDataSinkListener(this);
    fileDone = false;

    // OK, we can now start the actual transcoding.
    try {
      processor.start();
      dsink.start();
    } catch (IOException e) {
      System.err.println("IO error during processing");
      return false;
    }

    // Wait for EndOfStream event.
    waitForFileDone();

    // Cleanup.
    try {
      dsink.close();
    } catch (Exception ignored) {
    }
    processor.removeControllerListener(this);

    return true;
  }

  /** Create the DataSink. */
  private DataSink createDataSink(Processor p, MediaLocator outML) {

    DataSource ds;

    if ((ds = p.getDataOutput()) == null) {
      System.err.println(
          "Something is really wrong: the processor does not have an output DataSource");
      return null;
    }

    DataSink dsink;

    try {
      // System.err.println("- create DataSink for: " + outML);
      dsink = Manager.createDataSink(ds, outML);
      dsink.open();
    } catch (Exception e) {
      System.err.println("Cannot create the DataSink: " + e);
      return null;
    }

    return dsink;
  }

  private final Object waitSync = new Object();
  private boolean stateTransitionOK = true;

  /**
   * Block until the processor has transitioned to the given state. Return false if the transition
   * failed.
   */
  private boolean isTransitioning(Processor p, int state) {
    synchronized (waitSync) {
      try {
        while (p.getState() < state && stateTransitionOK) waitSync.wait();
      } catch (Exception ignored) {
      }
    }
    return !stateTransitionOK;
  }

  /** Controller Listener. */
  public void controllerUpdate(ControllerEvent evt) {
    if (evt instanceof ConfigureCompleteEvent
        || evt instanceof RealizeCompleteEvent
        || evt instanceof PrefetchCompleteEvent)
      synchronized (waitSync) {
        stateTransitionOK = true;
        waitSync.notifyAll();
      }
    else if (evt instanceof ResourceUnavailableEvent)
      synchronized (waitSync) {
        stateTransitionOK = false;
        waitSync.notifyAll();
      }
    else if (evt instanceof EndOfMediaEvent) {
      evt.getSourceController().stop();
      evt.getSourceController().close();
    }
  }

  private final Object waitFileSync = new Object();
  private boolean fileDone = false;
  private boolean fileSuccess = true;

  /** Block until file writing is done. */
  private boolean waitForFileDone() {
    synchronized (waitFileSync) {
      try {
        while (!fileDone) waitFileSync.wait();
      } catch (Exception ignored) {
      }
    }
    return fileSuccess;
  }

  /** Event handler for the file writer. */
  public void dataSinkUpdate(DataSinkEvent evt) {

    if (evt instanceof EndOfStreamEvent)
      synchronized (waitFileSync) {
        fileDone = true;
        waitFileSync.notifyAll();
      }
    else if (evt instanceof DataSinkErrorEvent)
      synchronized (waitFileSync) {
        fileDone = true;
        fileSuccess = false;
        waitFileSync.notifyAll();
      }
  }

  private static void prUsage() {
    System.err.println(
        "Usage: java JpegImagesToMovie -w <width> -h <height> -f <frame rate> -o <output URL> <input JPEG file 1> <input JPEG file 2> ...");
    System.exit(-1);
  }

  /** Create a media locator from the given string. */
  private static MediaLocator createMediaLocator(String url) {

    MediaLocator ml;

    if (url.indexOf(":") > 0) return new MediaLocator(url);

    if (url.startsWith(File.separator)) {
      return new MediaLocator("file:" + url);
    } else {
      String file = "file:" + System.getProperty("user.dir") + File.separator + url;
      return new MediaLocator(file);
    }
  }

  // /////////////////////////////////////////////
  //
  // Inner classes.
  // /////////////////////////////////////////////

  /**
   * A DataSource to read from a list of JPEG image files and turn that into a stream of JMF
   * buffers. The DataSource is not seekable or positionable.
   */
  class ImageDataSource extends PullBufferDataSource {

    ImageSourceStream streams[];

    ImageDataSource(int width, int height, int frameRate, Vector<byte[]> images) {
      streams = new ImageSourceStream[1];
      streams[0] = new ImageSourceStream(width, height, frameRate, images);
    }

    public void setLocator(MediaLocator source) {}

    public MediaLocator getLocator() {
      return null;
    }

    /**
     * Content type is of RAW since we are sending buffers of video frames without a container
     * format.
     */
    public String getContentType() {
      return ContentDescriptor.RAW;
    }

    public void connect() {}

    public void disconnect() {}

    public void start() {}

    public void stop() {}

    /** Return the ImageSourceStreams. */
    public PullBufferStream[] getStreams() {
      return streams;
    }

    /**
     * We could have derived the duration from the number of frames and frame rate. But for the
     * purpose of this program, it's not necessary.
     */
    public Time getDuration() {
      return DURATION_UNKNOWN;
    }

    public Object[] getControls() {
      return new Object[0];
    }

    public Object getControl(String type) {
      return null;
    }
  }

  /** The source stream to go along with ImageDataSource. */
  class ImageSourceStream implements PullBufferStream {

    Vector<byte[]> images;
    int width, height;
    VideoFormat format;

    int nextImage = 0; // index of the next image to be read.
    boolean ended = false;

    ImageSourceStream(int width, int height, int frameRate, Vector<byte[]> images) {
      this.width = width;
      this.height = height;
      this.images = images;

      format =
          new VideoFormat(
              VideoFormat.JPEG,
              new Dimension(width, height),
              Format.NOT_SPECIFIED,
              Format.byteArray,
              (float) frameRate);
    }

    /** We should never need to block assuming data are read from files. */
    public boolean willReadBlock() {
      return false;
    }

    /** This is called from the Processor to read a frame worth of video data. */
    public void read(Buffer buf) {

      // Check if we've finished all the frames.
      if (nextImage >= images.size()) {
        // We are done. Set EndOfMedia.
        // System.err.println("Done reading all images.");
        buf.setEOM(true);
        buf.setOffset(0);
        buf.setLength(0);
        ended = true;
        return;
      }

      byte[] image = images.elementAt(nextImage);
      nextImage++;

      byte data[] = null;

      // Check the input buffer type & size.

      if (buf.getData() instanceof byte[]) data = (byte[]) buf.getData();

      // Check to see the given buffer is big enough for the frame.
      if (data == null || data.length < image.length) {
        data = new byte[image.length];
        buf.setData(data);
      }

      System.err.println(" - reading image of length: " + image.length);

      System.arraycopy(image, 0, data, 0, image.length);

      buf.setOffset(0);
      buf.setLength(image.length);
      buf.setFormat(format);
      buf.setFlags(buf.getFlags() | Buffer.FLAG_KEY_FRAME);
    }

    /** Return the format of each video frame. That will be JPEG. */
    public Format getFormat() {
      return format;
    }

    public ContentDescriptor getContentDescriptor() {
      return new ContentDescriptor(ContentDescriptor.RAW);
    }

    public long getContentLength() {
      return 0;
    }

    public boolean endOfStream() {
      return ended;
    }

    public Object[] getControls() {
      return new Object[0];
    }

    public Object getControl(String type) {
      return null;
    }
  }
}
