(ns more-speech.ui.formatters
  (:require [clojure.string :as string]
            [more-speech.nostr.util :as util]
            [more-speech.ui.swing.ui-context :refer :all]
            [more-speech.nostr.events :as events]
            )
  (:import (java.util Date)
           (java.text SimpleDateFormat)))

(defn format-time [time]
  (let [time (* time 1000)
        date (Date. (long time))]
    (.format (SimpleDateFormat. "MM/dd/yy kk:mm:ss") date))
  )

(defn make-date [date-string]
  (let [date (.parse (SimpleDateFormat. "MM/dd/yyyy") date-string)]
    (quot (.getTime date) 1000)))

(defn abbreviate [s n]
  (let [dots "..."]
    (if (<= (count s) n)
      s
      (str (subs s 0 (- n (count dots))) dots))))

(defn prepend> [text]
  (let [lines (string/split-lines text)
        lines (map #(str ">" %) lines)]
    (string/join "\n" lines)))

;(defn reformat-article [article width]
;  (let [first-line-end (.indexOf article "\n")
;        reply-line? (and (> first-line-end 0) (= \> (first article)))
;        blank-line (.lastIndexOf article "\n\n" width)
;        indentation (.indexOf article "\n ")
;        breakable-space (.lastIndexOf article " " width)
;        [break-point break-string skip]
;        (cond
;          reply-line? [first-line-end "\n" 1]
;          (< -1 indentation width) [indentation "\n " 2]
;          (>= blank-line 0) [blank-line "\n\n" 2]
;          (<= (count article) width) [(count article) "" 0]
;          (>= breakable-space 0) [breakable-space "\n" 1]
;          :else [width "\n" 0])]
;    (let [head (.substring article 0 break-point)
;          head (.replaceAll head "\n" " ")
;          tail (.substring article (+ skip break-point))]
;      (if (empty? tail)
;        head
;        (str head break-string (reformat-article tail width))))))

(defn format-user-id [user-id]
  (let [profiles (:profiles @(:event-context @ui-context))]
    (if (nil? user-id)
      ""
      (abbreviate (get-in profiles [user-id :name] (util/num32->hex-string user-id)) 20))))

(declare get-subject
         replace-references)

(defn format-header [{:keys [pubkey created-at tags] :as event}]
  (if (nil? event)
    "nil"
    (let [content (replace-references event)
          name (format-user-id pubkey)
          time (format-time created-at)
          subject (get-subject tags)
          [reply-id _ _] (events/get-references event)
          reply-mark (if (some? reply-id) "^" " ")
          header-text (-> content (string/replace \newline \~) (abbreviate 80))
          content (if (empty? subject)
                    header-text
                    (abbreviate (str subject "|" header-text) 80))]
      (format "%s %20s %s %s\n" reply-mark name time content))))

(defn format-reply [event]
  (let [content (replace-references event)
        content (prepend> content)
        header (format ">From: %s at %s on %s\n"
                       (format-user-id (:pubkey event))
                       (format-time (:created-at event))
                       (first (:relays event))
                       )]
    (str header ">---------------\n" content)))

(defn get-subject [tags]
  (if (empty? tags)
    nil
    (let [tag (first tags)]
      (if (= (first tag) :subject)
        (abbreviate (second tag) 90)
        (recur (rest tags))))))

(declare lookup-reference)

(defn replace-references [event]
  (let [padded-content (str " " (:content event) " ")
        pattern #"\#\[\d+\]"
        references (re-seq pattern padded-content)
        segments (string/split padded-content pattern)
        referents (mapv (partial lookup-reference event) references)
        referents (conj referents " ")
        ]
    (string/trim (apply str (interleave segments referents)))))

(defn lookup-reference [event reference]
  (let [profiles (:profiles @(:event-context @ui-context))
        ref-string (re-find #"\d+" reference)
        index (Integer/parseInt ref-string)
        tags (:tags event)]
    (if (>= index (count tags))
      reference
      (let [id-string (-> tags (nth index) second)
            id (util/hex-string->num id-string)
            name (get-in profiles [id :name])
            name (if (nil? name)
                   (str "id:" (abbreviate id-string 8))
                   name)]
        (str "@" name)))))

(defn html-escape [content]
  (string/escape content {\& "&amp;"
                          \< "&lt;"
                          \> "&gt;"
                          \" "&quot;"
                          \' "&#x27;"
                          \/ "&#x2F;"}))

(defn break-newlines [content]
  (string/replace content "\n" "<br>"))

(defn format-replies [content]
  (string/replace content " >" "\n>"))

;; https://daringfireball.net/2010/07/improved_regex_for_matching_urls
(def url-pattern #"(?i)\b(?:(?:[a-z][\w-]+:(?:/{1,3}|[a-z0-9%])|www\d{0,3}[.]|[a-z0-9.\-]+[.][a-z]{2,4}/)(?:[^\s()<>]+|\(?:(?:[^\s()<>]+|(?:\(?:[^\s()<>]+\)))*\))+(?:\(?:(?:[^\s()<>]+|(?:\(?:[^\s()<>]+\)))*\)|[^\s`!()\[\]{};:'\".,<>?«»“”‘’]))")

(defn linkify [url]
   (str "<a href=\"" url "\">" url "</a>"))

(defn segment-text-url [content]
  (let [url (re-find url-pattern content)]
    (cond
      (not (nil? url))
      (let [url-start-index (string/index-of content url)
            url-end-index (+ url-start-index (.length url))
            text-sub (subs content 0 url-start-index)
            url-sub (subs content url-start-index url-end-index)
            rest (subs content url-end-index)]
        (concat
          (if (empty? text-sub)
            [[:url url-sub]]
            [[:text text-sub] [:url url-sub]])
          (segment-text-url rest)))
      (not (empty? content)) (list [:text content])
      :else '())))

(defn reformat-article [article]
  (let [segments (segment-text-url article)]
    (reduce
      (fn [formatted-content [seg-type seg]]
          (cond
            (= seg-type :text)
            (str formatted-content
                 ((comp break-newlines html-escape format-replies) seg))
            (= seg-type :url)
            (str formatted-content (linkify seg))))
      ""
      segments)))
