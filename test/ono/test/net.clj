(ns ono.test.net
  (:require [gloss.io :as gio]
            [gloss.core :as gloss]
            [clojure.data.codec.base64 :as b64])
  (:use [ono.net]
        [midje.sweet])
  (:import java.util.Arrays))

;; Gloss frame tests

(fact (gio/decode frame (gio/encode frame [(flags :SETUP) "ok"])) => [128 "ok"] )
(fact (gio/decode frame (gio/encode frame [(bit-or (flags :DBOP) (flags :SETUP)) "{\"some\": \"json\"}"])) => [144 "{\"some\": \"json\"}"] )

(fact (first (gio/decode frame (gio/encode frame [(flags :COMPRESSED) (.getBytes "123456")]))) => 8 )
(fact (String. (second (gio/decode frame (gio/encode frame [(flags :COMPRESSED) (.getBytes "123456")])))) => "123456" )

(fact "about PeerData"
      (let [pd (peer-data)]
        (every? #(and (contains? pd %)
                      (instance? clojure.lang.Atom (% pd)))
                [:control-connections :dbsync-connections :peer-info])
        => truthy
        (contains? pd :udp-socket) => truthy)

      (peer-data :udp-socket :foo) => #(= (:udp-socket %) :foo))

(fact "about operations on PeerData"
      (let [pd (peer-data :udp-socket nil
                          :control-connections (atom {1 :conn1 2 :conn2})
                          :dbsync-connections (atom {1 :conn1 2 :conn2})
                          :peer-info (atom {1 {:host :foobar
                                               :ip   123}}))
            pd (set-udp-socket pd 55555)]
        (get-udp-socket pd) => #(not (nil? %))
        (get-connection pd :control-connections 1) => :conn1
        (get-connection pd :control-donnections 3) => nil?
        (get-connection pd :dbsync-connections 1) => :conn1
        (get-connection pd :dbsync-donnections 3) => nil?
        (get-peer-info pd 1 :host) => :foobar
        (get-peer-info pd 2 :host) => nil?
        (add-connection! pd :control-connections 3 :conn3) => (fn [pd]
                                                                (-> pd
                                                                    :control-connections
                                                                    deref
                                                                    (get 3)))))

;; Compression

(fact (let [compressed (b64/decode (.getBytes "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"))]
	(uncompress compressed) => "{ \"addedentries\" : [  ], \"command\" : \"setplaylistrevision\", \"guid\" : \"adeae3f8-ec4b-409e-86ab-6de055f215b3\", \"metadataUpdate\" : false, \"newrev\" : \"9ed70db7-a6f1-4422-ab4a-7eaa61d44ae0\", \"oldrev\" : \"f7793c28-b27c-421c-b319-01c08b7ed909\", \"orderedguids\" : [ \"ade04a2c-1681-458c-8ac1-ae15416d79c8\", \"3705d305-5185-4a47-a402-54e27569a5e2\", \"66690edd-d85f-4fe2-adb1-ab68ffed1582\", \"6feb78a9-2f8e-47c0-a515-ebae626b2b0e\", \"a8e3e7b2-a544-4fe3-a7a6-ddde50ebe7c6\", \"307a4e2e-2099-41d4-91e0-eb5abcf9de3c\", \"718afa24-5821-4c86-b58d-e9a39f87051d\", \"0ff0cdb0-449e-4550-8190-bbd7b00a6b4a\", \"2fb2e55a-85f8-4fd5-bfb1-66f4ca40cfe6\", \"89cb49c5-74f7-4427-8b76-3dffb0f2faaf\", \"5e6594f9-1d41-4d10-8cfb-7a7ce29415aa\", \"7e80529e-19ca-4c84-bb9f-f2e174607981\", \"c35f370d-d09c-477c-b4b0-9e48b6bdac32\", \"2ebf321e-ff26-42fb-9feb-94fb620581df\", \"b55e1f09-2b8a-4e33-8c09-9ab7e6b6affc\", \"d8800074-cea0-44b4-b3b3-12d8c5fac840\", \"44656522-8911-4059-b73c-0f3f506531e1\", \"f3cc8e7b-c1b5-4e90-bb78-8bc6fd18927f\", \"17c13954-69b4-467a-afc6-ad344954d32d\", \"59820b0c-8b4d-4764-87de-f2c7053594fd\", \"1665cae4-bb82-4a07-b99a-e53da8aa4f49\", \"5fd82545-1d72-4b30-b4e8-fd2bdb2e5c62\", \"1c5f256a-00a6-4681-b46f-89512431b321\", \"e1e0d873-d5df-4921-8f53-9400c95a05c7\", \"f85f0294-9fe9-4130-b26c-79958343b2e2\", \"63abfd01-1bbd-40f0-b38f-a707bbf6730d\", \"e2b6af17-3494-489f-b8c9-a981e25bb312\", \"ecfdca5e-e034-408e-9a64-7c4258b27973\", \"d85a8484-f615-48c1-9098-1c985d8ff6a1\", \"ef9bb13b-3370-4547-b471-d24186c5086b\", \"113cf5cf-beec-4002-a30e-8e6356b3b0fb\", \"b50a4976-b1c7-4ece-9e24-7c995f8f33a1\", \"810823b8-aec3-49a3-a828-6741b95beb83\", \"fd81b28b-6bc3-4671-8e05-e1eeffc6b708\", \"359b3933-1c42-410c-9d11-60f37a2f00a9\", \"ad312e93-e692-4520-9df7-724d1f4c927c\", \"a1296153-9344-4141-8589-830637e6c401\", \"f71f4e23-ff12-4127-a05f-1f6ea22a7d22\", \"60ce6ba6-d4af-4603-ac7c-06107e68a6ab\", \"c5c47732-4f85-4e25-ae13-e7556c9b5443\", \"4b3a9477-1a62-49f0-853d-f82abdb5d60e\", \"f10fe3fe-e691-43b3-80b8-7946d1b3ffcb\", \"950580e8-ee42-4629-8aa1-6478853ba0f9\", \"b35d88ce-0f70-41f4-b5b8-89290e16897a\", \"c4c2e6ba-9231-4e4d-a95b-cc814c9ee791\", \"f8a8495c-a153-4183-9017-1058062f16ca\", \"b00e4447-32d8-4ea5-9e2b-a9c2c01bed36\", \"553b24ec-caf0-4b05-99af-8498fcd14e83\", \"96e5fc87-469a-4c58-bb40-f0c5f652e7e8\", \"77772dc2-3d47-443e-a749-06d8e64bf20c\", \"82c6861b-a857-4cd3-8a6e-ae4860a5a54a\", \"8be7273b-612c-4fdf-a3ab-3d89aac56279\", \"66f0abdd-a7ad-4633-8256-b078198bb424\", \"757a8832-40b7-47f4-98c7-58657170220a\", \"cad2e35c-b7c6-45f4-8aca-9db2ea89a1cb\", \"0311f822-587e-4b4e-9b94-f8ed3a2a9ce6\", \"340a3c21-d34d-408b-9554-0e6e0f8df966\", \"26d95902-ed37-4cf2-8af8-e40ddb02b0e1\", \"5713c348-cc08-4d66-a659-28b192f16789\", \"c4e9c59c-7be6-4093-820c-b97857a4b375\", \"265b0d84-8986-4d92-af15-c24afd323814\", \"45aaec87-8de0-41d5-a49a-5ff2d7e32ff7\", \"bbb8cb17-57fa-4152-8ccb-f177299b0826\", \"a1ada353-b53f-4a32-a1cc-b863714b2ef5\", \"de0196e5-73fb-44a9-a65f-8613d3b2c966\", \"79ffb719-aa5e-48c9-b460-eb35244f6e74\", \"ab0fa4f3-7308-4643-b0f7-827c75eddd51\", \"44d37ce3-8e23-43a8-8105-81b9dad08fd4\", \"969199fb-1077-442a-b37f-0200111cab69\", \"2daa685d-c725-4a08-be89-d1fafec165af\", \"4da89c1e-e528-44a5-a5e5-67806d4eca88\", \"d4ceecf0-fdc3-4a4b-9017-8a9d18620167\", \"b3cf6676-5eeb-4c0b-a5a4-9fba297a850b\", \"3fef7ebb-6fab-4928-b68b-4d0d22e6a76b\", \"93390b07-e569-47cf-b59f-a94782d75526\", \"9f9fffad-e920-4cc5-86e8-dd203abc0399\", \"a31bccc9-b905-4957-8146-7f14e4ac3046\", \"38cc55d8-76bf-47d3-bbbc-4f43d194a5e2\", \"b00f6c4e-15a5-4df9-a900-644a7d5e5886\", \"412ecd31-a1c6-4c75-9020-81ad8f995803\", \"2678096f-8921-4765-b0da-9084063988bd\", \"a4ad616f-aa63-4a31-ac12-7a0782835373\", \"49e0c308-0b7f-408f-8fa6-6eccf494ad17\", \"79e91a38-cfe6-4158-bbe7-5a82e3e68c44\", \"230671a5-573b-4b25-b5d9-6b5589331761\", \"35482582-730b-4e25-ac90-1764d1670cf9\", \"c244019f-a5f8-4b81-a15d-4049c01f3646\", \"f343a31b-83ba-4258-96cc-b0cccb581f0b\", \"45709da5-48ef-4318-a5dd-1ede6b8c7947\", \"7e5f33c4-4f50-493d-8f60-c041c3c4de87\", \"12cf028a-9817-4c19-9ea2-f6c2d780aaef\", \"cfb1a0be-3bf1-4084-913a-8aa054ebbae3\", \"bd684566-caa1-4658-a6c7-d8b0f02a309b\", \"8fcf76e1-7791-4c29-8e7c-a53c19ba027a\", \"9364fd98-4df0-425b-92e5-6cafdc54ce2b\", \"3f55195e-bc41-4b3a-9e62-624dd07755c5\", \"ea180cd0-b68d-498c-8a95-950f0867ed6a\", \"068b6e82-dc98-4444-b26d-96ff49485b59\", \"e01d52c3-fb60-44cf-b303-d76789f8f582\", \"5037449b-5496-4d9a-acc3-19955115380c\" ], \"playlistguid\" : \"af196381-d9fe-4dac-b11e-e1c2f194132b\" }"))























































