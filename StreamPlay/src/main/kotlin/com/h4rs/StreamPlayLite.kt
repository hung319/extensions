package com.h4rs

import android.os.Build
import androidx.annotation.RequiresApi
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.runAllAsync
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.h4rs.StreamPlayExtractor.invoke2embed
import com.h4rs.StreamPlayExtractor.invokeAllMovieland
import com.h4rs.StreamPlayExtractor.invokeAnimes
import com.h4rs.StreamPlayExtractor.invokeElevenmovies
import com.h4rs.StreamPlayExtractor.invokeKisskh
import com.h4rs.StreamPlayExtractor.invokeKisskhAsia
import com.h4rs.StreamPlayExtractor.invokeMovieBox
import com.h4rs.StreamPlayExtractor.invokeNepu
import com.h4rs.StreamPlayExtractor.invokeNinetv
import com.h4rs.StreamPlayExtractor.invokeRidomovies
import com.h4rs.StreamPlayExtractor.invokeRiveStream
import com.h4rs.StreamPlayExtractor.invokeShowflix
import com.h4rs.StreamPlayExtractor.invokeSoapy
import com.h4rs.StreamPlayExtractor.invokeStreamPlay
import com.h4rs.StreamPlayExtractor.invokeSubtitleAPI
import com.h4rs.StreamPlayExtractor.invokeSuperstream
import com.h4rs.StreamPlayExtractor.invokeVidSrcXyz
import com.h4rs.StreamPlayExtractor.invokeVidlink
import com.h4rs.StreamPlayExtractor.invokeVidsrccc
import com.h4rs.StreamPlayExtractor.invokeVidzee
import com.h4rs.StreamPlayExtractor.invokeWatch32APIHQ
import com.h4rs.StreamPlayExtractor.invokeWatchsomuch
import com.h4rs.StreamPlayExtractor.invokeWyZIESUBAPI
import com.h4rs.StreamPlayExtractor.invokeZoechip
import com.h4rs.StreamPlayExtractor.invokeZshow
import com.h4rs.StreamPlayExtractor.invokemorph
import com.h4rs.StreamPlayExtractor.invokevidrock
import com.h4rs.StreamPlayExtractor.sharedPref

class StreamPlayLite() : StreamPlay(sharedPref) {
    override var name = "StreamPlay-Lite"

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val token = sharedPref?.getString("token", null)
        val res = AppUtils.parseJson<LinkData>(data)
        runAllAsync(
            {
                if (!res.isAnime) invokeWatchsomuch(
                    res.imdbId,
                    res.season,
                    res.episode,
                    subtitleCallback
                )
            },
            {
                if (!res.isAnime) invokeNinetv(
                    res.id,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                if (res.isAnime) invokeAnimes(
                    res.title,
                    res.jpTitle,
                    res.date,
                    res.airedDate,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback,
                    res.isDub,
                    res.isMovie
                )
            },
            {
                if (res.isAsian) invokeKisskh(
                    res.title,
                    res.season,
                    res.episode,
                    res.lastSeason,
                    subtitleCallback,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeRidomovies(
                    res.id,
                    res.imdbId,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeAllMovieland(res.imdbId, res.season, res.episode, callback)
            },
            {
                if (!res.isAnime) invoke2embed(
                    res.imdbId,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                if (!res.isAsian && !res.isBollywood &&!res.isAnime) invokeZshow(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeShowflix(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeZoechip(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeNepu(
                    res.title,
                    res.airedYear ?: res.year,
                    res.season,
                    res.episode,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeWatch32APIHQ(
                    res.title,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeVidsrccc(
                    res.id,
                    res.season,
                    res.episode,
                    callback
                )
            },
            {
                invokeRiveStream(
                    res.id,
                    res.season,
                    res.episode,
                    callback
                )

            },
            {
                invokeSuperstream(
                    token,
                    res.imdbId,
                    res.season,
                    res.episode,
                    callback
                )
            },
            {
                invokeStreamPlay(
                    res.id,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeVidSrcXyz(
                    res.imdbId,
                    res.season,
                    res.episode,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeElevenmovies(
                    res.id,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeVidzee(
                    res.id,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeMovieBox(res.title, res.season, res.episode, subtitleCallback, callback)
            },
            {
                if (!res.isAnime) invokemorph(res.title,res.year, res.season, res.episode, subtitleCallback, callback)
            },
            {
                if (!res.isAnime) invokevidrock(res.id, res.season, res.episode, callback)
            },
            {
                if (!res.isAnime) invokeSoapy(res.id, res.season, res.episode, subtitleCallback,callback)
            },
            {
                if (!res.isAnime) invokeVidlink(res.id, res.season, res.episode, subtitleCallback,callback)
            },
            {
                if (!res.isAnime) invokeKisskhAsia(
                    res.id,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },

            //Subtitles Invokes
            {
                invokeSubtitleAPI(
                    res.imdbId,
                    res.season,
                    res.episode,
                    subtitleCallback
                )
            },
            {
                invokeWyZIESUBAPI(
                    res.imdbId,
                    res.season,
                    res.episode,
                    subtitleCallback,
                )
            },
        )
        return true
    }

}