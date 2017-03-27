/*
 * MIT License
 *
 * Copyright (c) 2017 Frederik Ar. Mikkelsen
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package fredboat.audio.source;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.regex.Matcher;

import org.slf4j.LoggerFactory;

import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;

import fredboat.audio.AbstractPlayer;

public class PlaylistImportSourceManager implements AudioSourceManager {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(PlaylistImportSourceManager.class);

    private static final AudioPlayerManager PRIVATE_MANAGER = AbstractPlayer
            .registerSourceManagers(new DefaultAudioPlayerManager());

    @Override
    public String getSourceName() {
        return "playlist_import";
    }

    @Override
    public AudioItem loadItem(DefaultAudioPlayerManager manager, AudioReference ar) {

        String pasteId;
        String response;
        Matcher m;
        Matcher serviceNameMatcher = PasteServiceConstants.SERVICE_NAME_PATTERN.matcher(ar.identifier);
        if (!serviceNameMatcher.find()) {
            log.debug("Failed to match the paste service name in the identifier " + ar.identifier);
            return null;
        }
        String serviceName = serviceNameMatcher.group(1).trim().toLowerCase();

        switch (serviceName) {

        case "hastebin":
            m = PasteServiceConstants.HASTEBIN_PATTERN.matcher(ar.identifier);
            pasteId = m.find() ? m.group(1) : null;
            break;

        case "pastebin":
            m = PasteServiceConstants.PASTEBIN_PATTERN.matcher(ar.identifier);
            pasteId = m.find() ? m.group(1) : null;
            break;

        default:
            log.debug("Failed to recognize the paste service");
            return null;
        }

        if (pasteId == null || !PasteServiceConstants.PASTE_SERVICE_URLS.containsKey(pasteId)) {
            log.debug("Failed to match the paste service name or service not supported: " + pasteId);
            return null;
        }

        try {
            response = Unirest.get(PasteServiceConstants.PASTE_SERVICE_URLS.get(serviceName) + pasteId).asString()
                    .getBody();
        } catch (UnirestException ex) {
            throw new FriendlyException(
                    "Couldn't load playlist. Either " + serviceName + " is down or the playlist does not exist.",
                    FriendlyException.Severity.FAULT, ex);
        }

        String[] unfiltered = response.split("\\s");
        ArrayList<String> filtered = new ArrayList<>();
        for (String str : unfiltered) {
            if (!str.equals("")) {
                filtered.add(str);
            }
        }

        PasteServiceAudioResultHandler handler = new PasteServiceAudioResultHandler();
        Future<Void> lastFuture = null;
        for (String id : filtered) {
            lastFuture = PRIVATE_MANAGER.loadItemOrdered(handler, id, handler);
        }

        if (lastFuture == null) {
            return null;
        }

        try {
            lastFuture.get();
        } catch (InterruptedException | ExecutionException ex) {
            throw new FriendlyException("Failed loading playlist item", FriendlyException.Severity.FAULT, ex);
        }

        return new BasicAudioPlaylist(pasteId, handler.getLoadedTracks(), null, false);
    }

    @Override
    public boolean isTrackEncodable(AudioTrack track) {
        return false;
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) throws IOException {
        throw new UnsupportedOperationException("This source manager is only for loading playlists");
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
        throw new UnsupportedOperationException("This source manager is only for loading playlists");
    }

    @Override
    public void shutdown() {
    }

    private class PasteServiceAudioResultHandler implements AudioLoadResultHandler {

        private final List<AudioTrack> loadedTracks;

        private PasteServiceAudioResultHandler() {
            this.loadedTracks = new ArrayList<>();
        }

        @Override
        public void trackLoaded(AudioTrack track) {
            loadedTracks.add(track);
        }

        @Override
        public void playlistLoaded(AudioPlaylist playlist) {
            log.info("Attempt to load a playlist recursively, skipping");
        }

        @Override
        public void noMatches() {
            // ignore
        }

        @Override
        public void loadFailed(FriendlyException exception) {
            log.debug("Failed loading track provided via the paste service", exception);
        }

        public List<AudioTrack> getLoadedTracks() {
            return loadedTracks;
        }

    }

}
