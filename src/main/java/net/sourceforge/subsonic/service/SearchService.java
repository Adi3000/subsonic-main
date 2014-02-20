/*
 This file is part of Subsonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2009 (C) Sindre Mehus
 */
package net.sourceforge.subsonic.service;

import static net.sourceforge.subsonic.service.SearchService.IndexType.ALBUM;
import static net.sourceforge.subsonic.service.SearchService.IndexType.ALBUM_ID3;
import static net.sourceforge.subsonic.service.SearchService.IndexType.ARTIST;
import static net.sourceforge.subsonic.service.SearchService.IndexType.ARTIST_ID3;
import static net.sourceforge.subsonic.service.SearchService.IndexType.SONG;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.sourceforge.subsonic.Logger;
import net.sourceforge.subsonic.dao.AlbumDao;
import net.sourceforge.subsonic.dao.ArtistDao;
import net.sourceforge.subsonic.domain.Album;
import net.sourceforge.subsonic.domain.Artist;
import net.sourceforge.subsonic.domain.MediaFile;
import net.sourceforge.subsonic.domain.MusicFolder;
import net.sourceforge.subsonic.domain.RandomSearchCriteria;
import net.sourceforge.subsonic.domain.SearchCriteria;
import net.sourceforge.subsonic.domain.SearchResult;
import net.sourceforge.subsonic.util.FileUtil;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.miscellaneous.LimitTokenCountAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.NumericRangeQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;

/**
 * Performs Lucene-based searching and indexing.
 *
 * @author Sindre Mehus
 * @version $Id$
 * @see MediaScannerService
 */
public class SearchService {

	private static final Logger LOG = Logger.getLogger(SearchService.class);

    private static final String FIELD_ID = "id";
    private static final String FIELD_TITLE = "title";
    private static final String FIELD_ALBUM = "album";
    private static final String FIELD_ARTIST = "artist";
    private static final String FIELD_GENRE = "genre";
    private static final String FIELD_YEAR = "year";
    private static final String FIELD_MEDIA_TYPE = "mediaType";
    private static final String FIELD_FOLDER = "folder";

    private static final Version LUCENE_VERSION = Version.LUCENE_45;

    private MediaFileService mediaFileService;
    private SettingsService settingsService;
    private ArtistDao artistDao;
    private AlbumDao albumDao;

    private IndexWriter artistWriter;
    private IndexWriter artistId3Writer;
    private IndexWriter albumWriter;
    private IndexWriter albumId3Writer;
    private IndexWriter songWriter;

    public SearchService() {
        removeLocks();
    }


    public void startIndexing() {
        try {
            artistWriter = createIndexWriter(ARTIST);
            artistId3Writer = createIndexWriter(ARTIST_ID3);
            albumWriter = createIndexWriter(ALBUM);
            albumId3Writer = createIndexWriter(ALBUM_ID3);
            songWriter = createIndexWriter(SONG);
        } catch (Exception x) {
            LOG.error("Failed to create search index.", x);
        }
    }

    public void index(MediaFile mediaFile) {
        try {
            if (mediaFile.isFile()) {
                songWriter.addDocument(SONG.createDocument(mediaFile));
            } else if (mediaFile.isAlbum()) {
                albumWriter.addDocument(ALBUM.createDocument(mediaFile));
            } else {
                artistWriter.addDocument(ARTIST.createDocument(mediaFile));
            }
        } catch (Exception x) {
            LOG.error("Failed to create search index for " + mediaFile, x);
        }
    }

    public void index(Artist artist) {
        try {
            artistId3Writer.addDocument(ARTIST_ID3.createDocument(artist));
        } catch (Exception x) {
            LOG.error("Failed to create search index for " + artist, x);
        }
    }

    public void index(Album album) {
        try {
            albumId3Writer.addDocument(ALBUM_ID3.createDocument(album));
        } catch (Exception x) {
            LOG.error("Failed to create search index for " + album, x);
        }
    }

    public void stopIndexing() {
        FileUtil.closeQuietly(artistId3Writer);
        FileUtil.closeQuietly(artistWriter);
        FileUtil.closeQuietly(albumWriter);
        FileUtil.closeQuietly(albumId3Writer);
        FileUtil.closeQuietly(songWriter);
    }

    public SearchResult search(SearchCriteria criteria, IndexType indexType) {
        SearchResult result = new SearchResult();
        int offset = criteria.getOffset();
        int count = criteria.getCount();
        result.setOffset(offset);

        IndexReader reader = null;
        try {
            reader = createIndexReader(indexType);
            IndexSearcher searcher = new IndexSearcher(reader);
            Analyzer analyzer = new StandardAnalyzer(LUCENE_VERSION);

            MultiFieldQueryParser queryParser = new MultiFieldQueryParser(LUCENE_VERSION, indexType.getFields(), analyzer, indexType.getBoosts());
            Query query = queryParser.parse(criteria.getQuery());

            TopDocs topDocs = searcher.search(query, null, offset + count);
            result.setTotalHits(topDocs.totalHits);

            int start = Math.min(offset, topDocs.totalHits);
            int end = Math.min(start + count, topDocs.totalHits);
            LOG.debug("Index ["+indexType.name()+"] retrieve " +  topDocs.totalHits + " songs with query : " + query);
            for (int i = start; i < end; i++) {
                Document doc = searcher.doc(topDocs.scoreDocs[i].doc);
                switch (indexType) {
                    case SONG:
                    case ARTIST:
                    case ALBUM:
                        MediaFile mediaFile = mediaFileService.getMediaFile(Integer.valueOf(doc.get(FIELD_ID)));
                        addIfNotNull(mediaFile, result.getMediaFiles());
                        break;
                    case ARTIST_ID3:
                        Artist artist = artistDao.getArtist(Integer.valueOf(doc.get(FIELD_ID)));
                        addIfNotNull(artist, result.getArtists());
                        break;
                    case ALBUM_ID3:
                        Album album = albumDao.getAlbum(Integer.valueOf(doc.get(FIELD_ID)));
                        addIfNotNull(album, result.getAlbums());
                        break;
                    default:
                        break;
                }
            }

        } catch (Throwable x) {
            LOG.error("Failed to execute Lucene search.", x);
        } finally {
            FileUtil.closeQuietly(reader);
        }
        return result;
    }

    /**
     * Returns a number of random songs.
     *
     * @param criteria Search criteria.
     * @return List of random songs.
     */
    public List<MediaFile> getRandomSongs(RandomSearchCriteria criteria) {
        List<MediaFile> result = new ArrayList<MediaFile>();

        String musicFolderPath = null;
        if (criteria.getMusicFolderId() != null) {
            MusicFolder musicFolder = settingsService.getMusicFolderById(criteria.getMusicFolderId());
            musicFolderPath = musicFolder.getPath().getPath();
        }

        IndexReader reader = null;
        try {
            reader = createIndexReader(SONG);
            IndexSearcher searcher = new IndexSearcher(reader);

            BooleanQuery query = new BooleanQuery();
            query.add(new TermQuery(new Term(FIELD_MEDIA_TYPE, MediaFile.MediaType.MUSIC.name().toLowerCase())), BooleanClause.Occur.MUST);
            if (criteria.getGenre() != null) {
                query.add(new TermQuery(new Term(FIELD_GENRE, criteria.getGenre().toLowerCase())), BooleanClause.Occur.MUST);
            }
            if (criteria.getFromYear() != null || criteria.getToYear() != null) {
                NumericRangeQuery<Integer> rangeQuery = NumericRangeQuery.newIntRange(FIELD_YEAR, criteria.getFromYear(), criteria.getToYear(), true, true);
                query.add(rangeQuery, BooleanClause.Occur.MUST);
            }
            if (musicFolderPath != null) {
                query.add(new TermQuery(new Term(FIELD_FOLDER, musicFolderPath)), BooleanClause.Occur.MUST);
            }

            TopDocs topDocs = searcher.search(query, null, Integer.MAX_VALUE);
            Random random = new Random(System.currentTimeMillis());
            LOG.info("Index retrieve random song : " +  topDocs.totalHits + " songs with query : " + query);
            for (int i = 0; i < Math.min(criteria.getCount(), topDocs.totalHits); i++) {
                int index = random.nextInt(topDocs.totalHits);
                Document doc = searcher.doc(topDocs.scoreDocs[index].doc);
                int id = Integer.valueOf(doc.get(FIELD_ID));
                try {
                    result.add(mediaFileService.getMediaFile(id));
                } catch (Exception x) {
                    LOG.warn("Failed to get media file " + id);
                }
            }

        } catch (Throwable x) {
            LOG.error("Failed to search or random songs.", x);
        } finally {
            FileUtil.closeQuietly(reader);
        }
        return result;
    }

    /**
     * Returns a number of random albums.
     *
     * @param count Number of albums to return.
     * @return List of random albums.
     */
    public List<MediaFile> getRandomAlbums(int count) {
        List<MediaFile> result = new ArrayList<MediaFile>();

        IndexReader reader = null;
        try {
            reader = createIndexReader(ALBUM);
            IndexSearcher searcher = new IndexSearcher(reader);

            Query query = new MatchAllDocsQuery();
            TopDocs topDocs = searcher.search(query, null, Integer.MAX_VALUE);
            Random random = new Random(System.currentTimeMillis());

            LOG.debug("Index retrieve " +  topDocs.totalHits + " albums with query : " + query);
            for (int i = 0; i < Math.min(count, topDocs.totalHits); i++) {
                int index = random.nextInt(topDocs.totalHits);
                Document doc = searcher.doc(topDocs.scoreDocs[index].doc);
                int id = Integer.valueOf(doc.get(FIELD_ID));
                try {
                    addIfNotNull(mediaFileService.getMediaFile(id), result);
                } catch (Exception x) {
                    LOG.warn("Failed to get media file " + id, x);
                }
            }

        } catch (Throwable x) {
            LOG.error("Failed to search for random albums.", x);
        } finally {
            FileUtil.closeQuietly(reader);
        }
        return result;
    }

    /**
     * Returns a number of random albums, using ID3 tag.
     *
     * @param count Number of albums to return.
     * @return List of random albums.
     */
    public List<Album> getRandomAlbumsId3(int count) {
        List<Album> result = new ArrayList<Album>();

        IndexReader reader = null;
        try {
            reader = createIndexReader(ALBUM_ID3);
            IndexSearcher searcher = new IndexSearcher(reader);

            Query query = new MatchAllDocsQuery();
            TopDocs topDocs = searcher.search(query, null, Integer.MAX_VALUE);
            Random random = new Random(System.currentTimeMillis());

            LOG.debug("Index retrieve " +  topDocs.totalHits + " albumsId3 with query : " + query);
            for (int i = 0; i < Math.min(count, topDocs.totalHits); i++) {
                int index = random.nextInt(topDocs.totalHits);
                Document doc = searcher.doc(topDocs.scoreDocs[index].doc);
                int id = Integer.valueOf(doc.get(FIELD_ID));
                try {
                    addIfNotNull(albumDao.getAlbum(id), result);
                } catch (Exception x) {
                    LOG.warn("Failed to get album file " + id, x);
                }
            }

        } catch (Throwable x) {
            LOG.error("Failed to search for random albums.", x);
        } finally {
            FileUtil.closeQuietly(reader);
        }
        return result;
    }

    private <T> void addIfNotNull(T value, List<T> list) {
        if (value != null) {
            list.add(value);
        }
    }
    private IndexWriter createIndexWriter(IndexType indexType) throws IOException {
        File dir = getIndexDirectory(indexType);
        IndexWriterConfig indexWriterConfig = new IndexWriterConfig(LUCENE_VERSION, new LimitTokenCountAnalyzer(new StandardAnalyzer(LUCENE_VERSION), 10));
        indexWriterConfig.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        IndexWriter indexWriter = new IndexWriter(FSDirectory.open(dir), indexWriterConfig);
        return indexWriter;
    }

    private IndexReader createIndexReader(IndexType indexType) throws IOException {
        File dir = getIndexDirectory(indexType);
        
        return DirectoryReader.open(FSDirectory.open(dir));
    }

    private File getIndexRootDirectory() {
        return new File(SettingsService.getSubsonicHome(), "lucene2");
    }

    private File getIndexDirectory(IndexType indexType) {
        return new File(getIndexRootDirectory(), indexType.toString().toLowerCase());
    }

    private void removeLocks() {
        for (IndexType indexType : IndexType.values()) {
            Directory dir = null;
            try {
                dir = FSDirectory.open(getIndexDirectory(indexType));
                if (IndexWriter.isLocked(dir)) {
                    IndexWriter.unlock(dir);
                    LOG.info("Removed Lucene lock file in " + dir);
                }
            } catch (Exception x) {
                LOG.warn("Failed to remove Lucene lock file in " + dir, x);
            } finally {
                FileUtil.closeQuietly(dir);
            }
        }
    }

    public void setMediaFileService(MediaFileService mediaFileService) {
        this.mediaFileService = mediaFileService;
    }

    public void setSettingsService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public void setArtistDao(ArtistDao artistDao) {
        this.artistDao = artistDao;
    }

    public void setAlbumDao(AlbumDao albumDao) {
        this.albumDao = albumDao;
    }

    public static enum IndexType {

        SONG(new String[]{FIELD_TITLE, FIELD_ARTIST}, FIELD_TITLE) {
            @Override
            public Document createDocument(MediaFile mediaFile) {
                Document doc = new Document();
                doc.add(new IntField(FIELD_ID, mediaFile.getId(), Field.Store.YES));
                doc.add(new Field(FIELD_MEDIA_TYPE, mediaFile.getMediaType().name(), Field.Store.NO, Field.Index.ANALYZED_NO_NORMS));

                if (mediaFile.getTitle() != null) {
                    doc.add(new Field(FIELD_TITLE, mediaFile.getTitle(), Field.Store.YES, Field.Index.ANALYZED));
                }
                if (mediaFile.getArtist() != null) {
                    doc.add(new Field(FIELD_ARTIST, mediaFile.getArtist(), Field.Store.YES, Field.Index.ANALYZED));
                }
                if (mediaFile.getGenre() != null) {
                    doc.add(new Field(FIELD_GENRE, mediaFile.getGenre(), Field.Store.NO, Field.Index.ANALYZED));
                }
                if (mediaFile.getYear() != null) {
                    doc.add(new IntField(FIELD_YEAR, mediaFile.getYear(), Field.Store.NO));
                }
                if (mediaFile.getFolder() != null) {
                    doc.add(new Field(FIELD_FOLDER, mediaFile.getFolder(), Field.Store.NO, Field.Index.NOT_ANALYZED_NO_NORMS));
                }

                return doc;
            }
        },

        ALBUM(new String[]{FIELD_ALBUM, FIELD_ARTIST}, FIELD_ALBUM) {
            @Override
            public Document createDocument(MediaFile mediaFile) {
                Document doc = new Document();
                doc.add(new IntField(FIELD_ID,mediaFile.getId(), Field.Store.YES));

                if (mediaFile.getArtist() != null) {
                    doc.add(new Field(FIELD_ARTIST, mediaFile.getArtist(), Field.Store.YES, Field.Index.ANALYZED));
                }
                if (mediaFile.getAlbumName() != null) {
                    doc.add(new Field(FIELD_ALBUM, mediaFile.getAlbumName(), Field.Store.YES, Field.Index.ANALYZED));
                }

                return doc;
            }
        },

        ALBUM_ID3(new String[]{FIELD_ALBUM, FIELD_ARTIST}, FIELD_ALBUM) {
            @Override
            public Document createDocument(Album album) {
                Document doc = new Document();
                doc.add(new IntField(FIELD_ID, album.getId(),Field.Store.YES));

                if (album.getArtist() != null) {
                    doc.add(new Field(FIELD_ARTIST, album.getArtist(), Field.Store.YES, Field.Index.ANALYZED));
                }
                if (album.getName() != null) {
                    doc.add(new Field(FIELD_ALBUM, album.getName(), Field.Store.YES, Field.Index.ANALYZED));
                }

                return doc;
            }
        },

        ARTIST(new String[]{FIELD_ARTIST}, null) {
            @Override
            public Document createDocument(MediaFile mediaFile) {
                Document doc = new Document();
                doc.add(new IntField(FIELD_ID, mediaFile.getId(), Field.Store.YES));

                if (mediaFile.getArtist() != null) {
                    doc.add(new Field(FIELD_ARTIST, mediaFile.getArtist(), Field.Store.YES, Field.Index.ANALYZED));
                }

                return doc;
            }
        },

        ARTIST_ID3(new String[]{FIELD_ARTIST}, null) {
            @Override
            public Document createDocument(Artist artist) {
                Document doc = new Document();
                doc.add(new IntField(FIELD_ID, artist.getId(), Field.Store.YES));
                doc.add(new Field(FIELD_ARTIST, artist.getName(), Field.Store.YES, Field.Index.ANALYZED));

                return doc;
            }
        };

        private final String[] fields;
        private final Map<String, Float> boosts;

        private IndexType(String[] fields, String boostedField) {
            this.fields = fields;
            boosts = new HashMap<String, Float>();
            if (boostedField != null) {
                boosts.put(boostedField, 2.0F);
            }
        }

        public String[] getFields() {
            return fields;
        }

        protected Document createDocument(MediaFile mediaFile) {
            throw new UnsupportedOperationException();
        }

        protected Document createDocument(Artist artist) {
            throw new UnsupportedOperationException();
        }

        protected Document createDocument(Album album) {
            throw new UnsupportedOperationException();
        }

        public Map<String, Float> getBoosts() {
            return boosts;
        }
    }

}


