import { GetServerSideProps } from "next";
import { serverSideTranslations } from "next-i18next/serverSideTranslations";
import { i18n } from "next-i18next.config";
import { getToken } from "next-auth/jwt";

// Where the account lives. Which language a page renders in is read from the account, and the
// account is the rebuild's to answer for.
const LINKWARDEN_API = process.env.LINKWARDEN_API || "http://localhost:9160";

const getServerSideProps: GetServerSideProps = async (ctx) => {
  const acceptLanguageHeader = ctx.req.headers["accept-language"];
  const availableLanguages = i18n.locales;

  const token = await getToken({ req: ctx.req });

  if (token && (token as any).apiToken) {
    const answer = await fetch(`${LINKWARDEN_API}/api/v1/users/me`, {
      headers: { Authorization: `Bearer ${(token as any).apiToken}` },
    })
      .then((it) => (it.ok ? it.json() : null))
      .catch(() => null);

    const user = answer?.response;
    if (user) {
      return {
        props: {
          ...(await serverSideTranslations(user.locale ?? "en", ["common"])),
        },
      };
    }
  }

  const acceptedLanguages = acceptLanguageHeader
    ?.split(",")
    .map((lang) => lang.split(";")[0]);

  let bestMatch = acceptedLanguages?.find((lang) =>
    availableLanguages.includes(lang)
  );

  if (!bestMatch) {
    acceptedLanguages?.some((acceptedLang) => {
      const partialMatch = availableLanguages.find((lang) =>
        lang.startsWith(acceptedLang)
      );
      if (partialMatch) {
        bestMatch = partialMatch;
        return true;
      }
      return false;
    });
  }

  return {
    props: {
      ...(await serverSideTranslations(bestMatch ?? "en", ["common"])),
    },
  };
};

export default getServerSideProps;
