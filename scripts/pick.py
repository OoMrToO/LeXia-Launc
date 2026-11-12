"""Builds pick.json: the day's film picks for the TV home screen.

Free sources only: this week's trending films from TMDB, scored with real IMDb
ratings from IMDb's public ratings file. Weak titles are dropped.
"""
import datetime
import gzip
import io
import json
import os
import urllib.request

TMDB = os.environ.get("TMDB_KEY", "")
IMDB_RATINGS = "https://datasets.imdbws.com/title.ratings.tsv.gz"
MIN_RATING = 6.5
MIN_VOTES = 1000
COUNT = 20


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "LeXia"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def get(url):
    return json.loads(fetch(url).decode("utf-8"))


def tmdb(path, **params):
    q = "&".join(f"{k}={v}" for k, v in params.items())
    return get(f"https://api.themoviedb.org/3/{path}?api_key={TMDB}&{q}")


def info(m, kind):
    is_tv = kind == "tv"
    date = (m.get("first_air_date") if is_tv else m.get("release_date")) or ""
    return {
        "id": m["id"],
        "type": kind,
        "title": (m.get("name") if is_tv else m.get("title")) or "",
        "year": date[:4],
        "kind": "Dizi" if is_tv else "Film",
        "rating": round(m.get("vote_average") or 0, 1),
        "overview": m["overview"],
        "backdrop": m["backdrop_path"],
        "poster": m.get("poster_path") or "",
        "release_date": date,
        "popularity": m.get("popularity") or 0,
        "genre_ids": m.get("genre_ids") or [],
    }


def key_of(c):
    return f'{c["type"]}-{c["id"]}'


def meta_of(c):
    parts = [c["kind"], c["year"]]
    if c.get("genres"):
        parts.append(", ".join(c["genres"][:2]))
    if c.get("imdb"):
        parts.append(("IMDb %.1f" % c["imdb"]).replace(".", ","))
    return "  ·  ".join(p for p in parts if p)


def video_label(v, lang):
    """What a trailer is, in the TV's words: dubbed or subtitled Turkish, the original, or a teaser."""
    name = (v.get("name") or "").lower()
    short = v.get("type") == "Teaser"
    if lang == "tr":
        if "dubla" in name:
            kind = "Türkçe dublaj"
        elif "altyaz" in name:
            kind = "Türkçe altyazılı"
        else:
            kind = "Türkçe"
    else:
        kind = "Orijinal"
    return kind + (" kısa tanıtım" if short else " fragman")


def trailers_of(c):
    """Every YouTube trailer and teaser, Turkish first then English, at most four; the first is the main one."""
    out, seen = [], set()
    for lang in ("tr-TR", "en-US"):
        try:
            res = tmdb(f'{c["type"]}/{c["id"]}/videos', language=lang)["results"]
        except Exception:
            continue
        for kind in ("Trailer", "Teaser"):
            for v in res:
                key = v.get("key") or ""
                if v.get("site") != "YouTube" or v.get("type") != kind or key in seen:
                    continue
                seen.add(key)
                out.append({"k": key, "n": video_label(v, lang[:2])})
    # Two trailers of the same kind read the same; number them so the choice is clear.
    names = [t["n"] for t in out]
    for t in out:
        if names.count(t["n"]) > 1:
            same = [x for x in out if x["n"] == t["n"]]
            for i, x in enumerate(same):
                if not x["n"].endswith(")"):
                    x["n"] = f'{x["n"]} ({i + 1})'
    return out[:4]


def trailer_of(c):
    t = trailers_of(c)
    return t[0]["k"] if t else ""


def logo_of(c):
    """Title artwork (PNG), Turkish first, like the Apple TV app."""
    try:
        logos = tmdb(f'{c["type"]}/{c["id"]}/images', include_image_language="tr,en,null").get("logos", [])
    except Exception:
        return ""
    logos = [x for x in logos if x.get("file_path", "").endswith(".png")]
    rank = {"tr": 0, "en": 1}
    logos.sort(key=lambda x: (rank.get(x.get("iso_639_1"), 2), -(x.get("vote_average") or 0)))
    return logos[0]["file_path"] if logos else ""


def candidates():
    out = []
    for page in (1, 2, 3):
        for m in tmdb("trending/movie/week", language="tr-TR", page=page)["results"]:
            if m.get("backdrop_path") and m.get("overview"):
                out.append(info(m, "movie"))
    seen = set()
    uniq = []
    for c in out:
        if key_of(c) not in seen:
            seen.add(key_of(c))
            uniq.append(c)
    return uniq


def genre_names():
    names = {}
    for kind in ("movie", "tv"):
        try:
            for g in tmdb(f"genre/{kind}/list", language="tr-TR")["genres"]:
                names.setdefault(g["id"], g["name"])
        except Exception:
            pass
    return names


def imdb_id_of(c):
    try:
        return tmdb(f'{c["type"]}/{c["id"]}/external_ids').get("imdb_id") or ""
    except Exception:
        return ""


def imdb_ratings(ids):
    """Look up (rating, votes) for the given IMDb ids in IMDb's public ratings file."""
    wanted = set(i for i in ids if i)
    found = {}
    if not wanted:
        return found
    try:
        data = fetch(IMDB_RATINGS)
    except Exception as e:
        print("IMDb puanları alınamadı:", e)
        return found
    with gzip.open(io.BytesIO(data), "rt", encoding="utf-8") as f:
        next(f, None)
        for line in f:
            tid = line[: line.find("\t")]
            if tid in wanted:
                _, rating, votes = line.rstrip("\n").split("\t")
                found[tid] = (float(rating), int(votes))
    return found


def details_of(c):
    """Runtime, tagline, director, cast and similar films for the detail page."""
    try:
        extra = "credits,recommendations,watch/providers" + (",release_dates" if c["type"] == "movie" else ",content_ratings")
        d = tmdb(f'{c["type"]}/{c["id"]}', language="tr-TR", append_to_response=extra)
    except Exception:
        return {}
    credits = d.get("credits") or {}
    cast = [
        {"name": p.get("name", ""), "role": p.get("character", ""), "photo": p.get("profile_path") or ""}
        for p in (credits.get("cast") or [])[:10]
        if p.get("name")
    ]
    if c["type"] == "tv":
        director = ", ".join(p.get("name", "") for p in (d.get("created_by") or [])[:2] if p.get("name"))
    else:
        director = next((p.get("name", "") for p in credits.get("crew") or [] if p.get("job") == "Director"), "")
    similar = []
    for m in (d.get("recommendations") or {}).get("results") or []:
        if m.get("media_type", c["type"]) != c["type"] or not m.get("backdrop_path") or not m.get("overview"):
            continue
        similar.append(info(m, c["type"]))
        if len(similar) == 8:
            break
    release = ""
    for r in (d.get("release_dates") or {}).get("results") or []:
        if r.get("iso_3166_1") == "TR":
            dates = sorted(x.get("release_date", "")[:10] for x in r.get("release_dates") or [] if x.get("type") in (2, 3))
            if dates:
                release = dates[0]
    # Where it streams in Turkey with a subscription ("Nerede izlenir"); names as people know them.
    tr_watch = ((d.get("watch/providers") or {}).get("results") or {}).get("TR") or {}
    where = []
    for p in sorted(tr_watch.get("flatrate") or [], key=lambda p: p.get("display_priority", 99)):
        n = PLATFORM_NAMES.get((p.get("provider_name") or "").strip(), (p.get("provider_name") or "").strip())
        if n and n not in where:
            where.append(n)
    return {
        "where": where[:4],
        "cert": cert_of(d, c["type"]),
        "next_ep": next_episode(d) if c["type"] == "tv" else {},
        "release": release,
        "runtime": (d.get("runtime") or 0) if c["type"] == "movie" else 0,
        "seasons": d.get("number_of_seasons") or 0,
        "tagline": d.get("tagline") or "",
        "director": director,
        "cast": cast,
        "similar": similar,
    }


# US ratings as the nearest Turkish age band, used only when TMDB has no Turkish one.
US_AGE = {"G": "Genel", "PG": "7+", "PG-13": "13+", "R": "18+", "NC-17": "18+",
          "TV-Y": "Genel", "TV-G": "Genel", "TV-Y7": "7+", "TV-PG": "7+", "TV-14": "13+", "TV-MA": "18+"}


def age_band(v):
    """Turkish rating text as a short badge: "7+", "13+", "18+" or "Genel"."""
    v = (v or "").strip().upper()
    if not v:
        return ""
    if v.startswith("GENEL") or v in ("G", "GA"):
        return "Genel"
    digits = "".join(ch for ch in v if ch.isdigit())
    if digits and 0 < int(digits) <= 21:
        return digits + "+"
    return ""


def cert_of(d, kind):
    """Age rating: Turkey's if TMDB has it, else the US one mapped to a Turkish band."""
    found = {}
    if kind == "movie":
        for r in (d.get("release_dates") or {}).get("results") or []:
            for x in r.get("release_dates") or []:
                if x.get("certification"):
                    found.setdefault(r.get("iso_3166_1"), x["certification"])
    else:
        for r in (d.get("content_ratings") or {}).get("results") or []:
            if r.get("rating"):
                found.setdefault(r.get("iso_3166_1"), r["rating"])
    return age_band(found.get("TR")) or US_AGE.get((found.get("US") or "").upper(), "")


def next_episode(d):
    """The next episode's air date, season and number, if one is announced."""
    n = d.get("next_episode_to_air") or {}
    if not n.get("air_date"):
        return {}
    out = {"date": n["air_date"][:10], "season": n.get("season_number") or 0, "episode": n.get("episode_number") or 0}
    # "finale": the last episode of the season; "premiere": a new season (not the very first) starts.
    if n.get("episode_type") == "finale":
        out["kind"] = "finale"
    elif out["episode"] == 1 and out["season"] > 1:
        out["kind"] = "premiere"
    return out


def episodes(shows_now, today, names):
    """
    New-episode dates for this week's top series and for series seen in the top 10 within
    the last 120 days, so a show saved to "Listem" keeps its calendar after leaving the list.
    Series no longer in the top 10 carry their full record ("info": poster, story, cast, score),
    so the calendar and the weekend guide can open a full page for them.
    """
    old = []
    try:
        with open("pick.json", encoding="utf-8") as f:
            old = json.load(f).get("episodes") or []
    except Exception:
        pass
    limit = (datetime.datetime.strptime(today, "%Y-%m-%d") - datetime.timedelta(days=120)).strftime("%Y-%m-%d")
    out = {}
    for e in shows_now:
        out[e["id"]] = {"id": e["id"], "title": e["title"], "backdrop": e["backdrop"], "seen": today,
                        "next": e.get("next_ep") or {}}
    cands = {}
    for e in old:
        if e.get("id") in out or (e.get("seen") or "") < limit:
            continue
        try:
            d = tmdb(f'tv/{e["id"]}', language="tr-TR")
        except Exception:
            out[e["id"]] = e
            continue
        if d.get("status") in ("Ended", "Canceled"):
            continue
        e["next"] = next_episode(d)
        out[e["id"]] = e
        # Only shows with an episode ahead are shown anywhere, so only they need the full record.
        if e["next"] and d.get("backdrop_path"):
            m = dict(d, overview=d.get("overview") or "", genre_ids=[g["id"] for g in d.get("genres") or []])
            cands[e["id"]] = info(m, "tv")
        else:
            e.pop("info", None)
    for c in cands.values():
        c["imdb_id"] = imdb_id_of(c)
    try:
        scores = imdb_ratings(c["imdb_id"] for c in cands.values())
    except Exception:
        scores = {}
    for i, c in cands.items():
        c["imdb"], c["votes"] = scores.get(c["imdb_id"], (0, 0))
        c["genres"] = [names[g] for g in c["genre_ids"] if g in names]
        try:
            out[i]["info"] = entry(c, names, True)
        except Exception as x:
            print("Dizi bilgisi alınamadı:", c["title"], x)
    return list(out.values())


def reason_of(c):
    r = c.get("imdb") or 0
    if c.get("type") == "tv":
        if r >= 8:
            return "IMDb'de haftanın en yüksek puanlı dizilerinden."
        return "Bu hafta en çok izlenen dizilerden."
    g = set(c.get("genres") or [])
    if r >= 8:
        return "IMDb'de haftanın en yüksek puanlı filmlerinden."
    if g & {"Korku", "Gerilim"}:
        return "Işıkları kapat, sesi aç."
    if "Belgesel" in g:
        return "Gerçek bir hikâye, merak uyandıran bir anlatım."
    if g & {"Animasyon", "Aile"}:
        return "Bütün aileyle izlemelik."
    if g & {"Komedi", "Romantik"}:
        return "Keyifli bir akşam için ideal."
    if r >= 7.3:
        return "İzleyicilerden yüksek puan alıyor."
    return "Bu hafta en çok izlenen filmlerden."


def choose(cands):
    """Best rated of the popular films."""
    good = [c for c in cands if c.get("imdb") and c["imdb"] >= MIN_RATING and c["votes"] >= MIN_VOTES]
    # New titles may not have enough IMDb votes yet; fall back to TMDB's own rating.
    rest = [c for c in cands if c not in good and not c.get("imdb") and c["rating"] >= MIN_RATING]
    pool = good + rest

    def score(c):
        return (c.get("imdb") or c["rating"]) * 10 + min(c["popularity"], 400) / 20

    picks = sorted(pool, key=lambda c: -score(c))[:COUNT]
    return picks or cands[:COUNT]


def cinema(today):
    """Films in Turkish cinemas now, and ones coming in the next two months (TR dates)."""
    def discover(**q):
        out = []
        try:
            for m in tmdb("discover/movie", language="tr-TR", region="TR", sort_by="popularity.desc", **q)["results"]:
                if m.get("poster_path") and m.get("overview"):
                    out.append(info(m, "movie"))
        except Exception as e:
            print("Vizyon listesi alınamadı:", e)
        return out
    start = (today - datetime.timedelta(days=35)).strftime("%Y-%m-%d")
    now = today.strftime("%Y-%m-%d")
    soon_from = (today + datetime.timedelta(days=1)).strftime("%Y-%m-%d")
    soon_to = (today + datetime.timedelta(days=60)).strftime("%Y-%m-%d")
    playing = discover(with_release_type="3", **{"release_date.gte": start, "release_date.lte": now})[:10]
    coming = discover(with_release_type="2%7C3", **{"release_date.gte": soon_from, "release_date.lte": soon_to})[:12]
    return playing, coming


# How platform names read on the TV ("YENİ · DISNEY+"); others keep TMDB's name.
PLATFORM_NAMES = {
    "Disney Plus": "Disney+", "Amazon Prime Video": "Prime Video", "Apple TV Store": "Apple TV",
    "Apple TV Plus": "Apple TV+", "TOD TV": "TOD", "HBO Max": "Max", "Netflix basic with Ads": "Netflix",
}


def platforms(today):
    """New on streaming in Turkey: a few films and series per subscription platform (Netflix, Disney+...).
    Only facts and pictures; nothing is played. Platforms come from TMDB's own list for Turkey."""
    try:
        provs = tmdb("watch/providers/movie", watch_region="TR", language="tr-TR")["results"]
    except Exception as e:
        print("Platform listesi alınamadı:", e)
        return []
    provs.sort(key=lambda p: (p.get("display_priorities") or {}).get("TR", p.get("display_priority", 99)))
    year_ago = (today - datetime.timedelta(days=365)).strftime("%Y-%m-%d")
    lately = (today - datetime.timedelta(days=45)).strftime("%Y-%m-%d")
    now = today.strftime("%Y-%m-%d")
    out, seen, used = [], set(), 0
    for p in provs:
        if used >= 8:
            break
        pid, pname = p.get("provider_id"), (p.get("provider_name") or "").strip()
        pname = PLATFORM_NAMES.get(pname, pname)
        if not pid or not pname:
            continue
        got = []
        for kind, extra in (("movie", {"primary_release_date.gte": year_ago, "primary_release_date.lte": now}),
                            ("tv", {"air_date.gte": lately, "air_date.lte": now})):
            try:
                res = tmdb(f"discover/{kind}", language="tr-TR", watch_region="TR", with_watch_providers=pid,
                           with_watch_monetization_types="flatrate", sort_by="popularity.desc",
                           **{"vote_count.gte": "80"}, **extra)["results"]
            except Exception:
                continue
            n = 0
            for m in res:
                # A few more candidates than shown: weak ones are dropped after the IMDb scores come in.
                if n >= (6 if kind == "movie" else 4):
                    break
                if not (m.get("backdrop_path") and m.get("overview")):
                    continue
                c = info(m, kind)
                if key_of(c) in seen:
                    continue
                seen.add(key_of(c))
                c["provider"] = pname
                got.append(c)
                n += 1
        # Rent-only stores (no subscription titles) give nothing here and are skipped.
        if got:
            used += 1
            out.extend(got)
    return out


def local_titles(today):
    """Popular Turkish films (last three years) and series, for the "Yerli yapımlar" row."""
    out = []
    since = (today - datetime.timedelta(days=3 * 365)).strftime("%Y-%m-%d")
    for kind, extra, n in (("movie", {"primary_release_date.gte": since}, 8), ("tv", {}, 6)):
        try:
            res = tmdb(f"discover/{kind}", language="tr-TR", with_origin_country="TR", sort_by="popularity.desc",
                       **{"vote_count.gte": "40"}, **extra)["results"]
        except Exception as e:
            print("Yerli yapımlar alınamadı:", e)
            continue
        got = 0
        for m in res:
            if got >= n + 4:
                break
            if m.get("backdrop_path") and m.get("poster_path") and m.get("overview"):
                out.append(info(m, kind))
                got += 1
    return out


def digital(today):
    """Films coming out digitally in Turkey in the next 45 days, for the calendar."""
    now = today.strftime("%Y-%m-%d")
    end = (today + datetime.timedelta(days=45)).strftime("%Y-%m-%d")
    out = []
    try:
        for m in tmdb("discover/movie", language="tr-TR", region="TR", with_release_type="4", sort_by="popularity.desc",
                      **{"release_date.gte": now, "release_date.lte": end, "vote_count.gte": "20"})["results"]:
            if m.get("poster_path") and m.get("backdrop_path") and m.get("overview"):
                out.append(info(m, "movie"))
    except Exception as e:
        print("Dijital çıkışlar alınamadı:", e)
    return out[:12]


def digital_date(c):
    """The Turkish digital (type 4) release date of one film, if TMDB has it."""
    try:
        for r in tmdb(f'movie/{c["id"]}/release_dates')["results"]:
            if r.get("iso_3166_1") == "TR":
                dates = sorted(x.get("release_date", "")[:10] for x in r.get("release_dates") or [] if x.get("type") == 4)
                if dates:
                    return dates[0]
    except Exception:
        pass
    return ""


def best_streaming(items, full):
    out, count = [], {}
    for c in items:
        if (c.get("imdb") or c["rating"]) < 6.5:
            continue
        k = (c["provider"], c["type"])
        if count.get(k, 0) >= (3 if c["type"] == "movie" else 2):
            continue
        count[k] = count.get(k, 0) + 1
        out.append(dict(full(c), provider=c["provider"]))
    return out


# Genre rows on the home screen: TMDB genre id and the Turkish name the app knows.
GENRE_ROWS = [
    (35, "Komedi"), (28, "Aksiyon"), (18, "Dram"), (53, "Gerilim"), (878, "Bilim-Kurgu"), (12, "Macera"),
    (80, "Suç"), (10749, "Romantik"), (16, "Animasyon"), (27, "Korku"), (14, "Fantastik"), (9648, "Gizem"),
    (10751, "Aile"), (99, "Belgesel"), (36, "Tarih"), (10402, "Müzik"), (10752, "Savaş"), (37, "Vahşi Batı"),
]
GENRE_COUNT = 10


def genre_candidates(today):
    """Well-liked, well-known films per genre; the page turns with the day so the rows change."""
    page = today.timetuple().tm_yday % 4 + 1
    out = {}
    for gid, name in GENRE_ROWS:
        films = []
        try:
            res = tmdb("discover/movie", language="tr-TR", sort_by="popularity.desc", with_genres=gid,
                       page=page, **{"vote_average.gte": "6.8", "vote_count.gte": "800"})["results"]
        except Exception as e:
            print("Tür listesi alınamadı:", name, e)
            continue
        # Smaller genres (war, westerns, music) run out of well-known films on later pages: start over.
        if len(res) < 6 and page > 1:
            try:
                res = tmdb("discover/movie", language="tr-TR", sort_by="popularity.desc", with_genres=gid,
                           page=1, **{"vote_average.gte": "6.8", "vote_count.gte": "800"})["results"]
            except Exception:
                pass
        for m in res:
            if m.get("backdrop_path") and m.get("poster_path") and m.get("overview"):
                films.append(info(m, "movie"))
        out[name] = films[:GENRE_COUNT]
    return out


def on_this_day(today):
    """
    Well-known films that came out on this day in earlier years (the screensaver's
    "Sinema tarihinde bugün"). Round anniversaries (5, 10, 25 years…) come first.
    """
    md = today.strftime("-%m-%d")
    found = []
    for year in range(today.year - 60, today.year - 4):
        day = f"{year}{md}"
        try:
            res = tmdb("discover/movie", language="tr-TR", sort_by="vote_count.desc",
                       **{"primary_release_date.gte": day, "primary_release_date.lte": day, "vote_count.gte": "1500"})["results"]
        except Exception:
            continue
        for m in res[:2]:
            if m.get("backdrop_path") and m.get("overview"):
                c = info(m, "movie")
                c["years"] = today.year - year
                c["votes_tmdb"] = m.get("vote_count") or 0
                found.append(c)
    # Round anniversaries first, then the best known.
    found.sort(key=lambda c: (0 if c["years"] % 5 == 0 else 1, -c["votes_tmdb"]))
    return found[:20]


def entry(c, names, full):
    """One film as the app reads it; [full] adds the detail page data."""
    e = {
        "type": c["type"],
        "id": c["id"],
        "title": c["title"],
        "meta": meta_of(c),
        "overview": c["overview"],
        "backdrop": c["backdrop"],
        "poster": c.get("poster") or "",
        "reason": reason_of(c),
        "imdb": c.get("imdb") or 0,
        "year": c["year"],
        "genres": c.get("genres") or [names[g] for g in c.get("genre_ids", []) if g in names],
        "runtime": 0,
        "trailer": "",
        "logo": "",
    }
    if not full:
        return e
    d = details_of(c)
    e["runtime"] = d.get("runtime", 0)
    e["seasons"] = d.get("seasons", 0)
    e["release"] = d.get("release") or c.get("release_date", "")
    e["tagline"] = d.get("tagline", "")
    e["cert"] = d.get("cert", "")
    if d.get("next_ep"):
        e["next_ep"] = d["next_ep"]
    if d.get("where"):
        e["where"] = d["where"]
    e["director"] = d.get("director", "")
    e["cast"] = d.get("cast", [])
    e["similar"] = [entry(s, names, False) for s in d.get("similar", [])]
    vids = trailers_of(c)
    e["trailer"] = vids[0]["k"] if vids else ""
    if vids:
        e["trailers"] = vids
    e["logo"] = logo_of(c)
    return e


def main():
    cands = candidates()
    shows = []
    try:
        for m in tmdb("trending/tv/week", language="tr-TR")["results"]:
            if m.get("poster_path") and m.get("backdrop_path") and m.get("overview"):
                shows.append(info(m, "tv"))
    except Exception as e:
        print("Diziler alınamadı:", e)
    shows = shows[:10]
    for c in cands + shows:
        c["imdb_id"] = imdb_id_of(c)
    ratings = imdb_ratings(c["imdb_id"] for c in cands + shows)
    names = genre_names()
    for c in cands + shows:
        c["imdb"], c["votes"] = ratings.get(c["imdb_id"], (0, 0))
        c["genres"] = [names[g] for g in c["genre_ids"] if g in names]
    picks = choose(cands)
    # Top 10: this week's most watched, in TMDB's trending order.
    top = [c for c in cands if c.get("poster")][:10]

    cache = {}

    def full(c):
        if key_of(c) not in cache:
            cache[key_of(c)] = entry(c, names, True)
        return cache[key_of(c)]

    now = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=3)
    playing, coming = cinema(now)
    by_genre = genre_candidates(now)
    history = on_this_day(now)
    streaming = platforms(now)
    local = local_titles(now)
    dig = digital(now)
    extra = playing + coming + history + streaming + local + dig + [c for films in by_genre.values() for c in films]
    for c in extra:
        c["imdb_id"] = imdb_id_of(c)
    more = imdb_ratings(c["imdb_id"] for c in extra)
    for c in extra:
        c["imdb"], c["votes"] = more.get(c["imdb_id"], (0, 0))
        c["genres"] = [names[g] for g in c["genre_ids"] if g in names]
    soon = [full(c) for c in coming]
    # Only keep films whose Turkish date is really ahead, soonest first.
    today = now.strftime("%Y-%m-%d")
    soon = sorted((e for e in soon if e.get("release", "") > today), key=lambda e: e["release"])
    out = {
        "date": now.strftime("%Y-%m-%d"),
        "picks": [full(c) for c in picks],
        "top10": [full(c) for c in top],
        "top10tv": [full(c) for c in shows],
        "cinema": [full(c) for c in playing],
        # New on streaming platforms in Turkey, with the platform's name.
        # Turkish films and series: IMDb 6.0 or more (local titles score lower abroad), at most 12.
        "local": [full(c) for c in local if (c.get("imdb") or c["rating"]) >= 6.0][:12],
        # Coming out digitally in Turkey, with that date (the calendar shows them).
        "digital": [d for d in (dict(full(c), digital=digital_date(c)) for c in dig) if d.get("digital", "") >= today][:10],
        # IMDb 6.5 or more, like the other rows; at most 3 films and 2 series per platform.
        "platforms": best_streaming(streaming, full),
        "soon": soon,
        # Only films worth seeing again: IMDb 6.5 or more (TMDB's score if IMDb has none yet).
        "history": [dict(full(c), years=c["years"]) for c in history if (c.get("imdb") or c["rating"]) >= 6.5][:8],
        # Weakest IMDb scores dropped; films without an IMDb score yet keep TMDB's.
        "genres": [
            {"name": name, "films": [full(c) for c in films if (c.get("imdb") or c["rating"]) >= 6.5]}
            for name, films in by_genre.items() if films
        ],
    }
    out["episodes"] = episodes(out["top10tv"], out["date"], names)
    with open("pick.json", "w", encoding="utf-8") as f:
        # Compact: the TV downloads this file several times a day.
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    print("Tamam:", len(out["picks"]), "film,", len(out["top10"]), "top 10 film,",
          len(out["top10tv"]), "top 10 dizi,", len(out["cinema"]), "vizyonda,",
          len(out["soon"]), "yakında,", sum(len(g["films"]) for g in out["genres"]), "türlere göre,",
          len(out["history"]), "tarihte bugün,", len(out["platforms"]), "platformlarda yeni,", len(out["local"]), "yerli,", len(out["digital"]), "dijital çıkış,",
          len(ratings), "IMDb puanı")


if __name__ == "__main__":
    main()
